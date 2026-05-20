// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative

import android.view.Surface
import com.skrdzavac.rtspnative.core.AccessUnit
import com.skrdzavac.rtspnative.core.RtpPacket
import com.skrdzavac.rtspnative.h264.H264Depacketizer
import com.skrdzavac.rtspnative.h265.H265Depacketizer
import com.skrdzavac.rtspnative.signaling.TrackInfo
import com.skrdzavac.rtspnative.videodecoder.H264VideoDecoder
import com.skrdzavac.rtspnative.videodecoder.H265VideoDecoder
import com.skrdzavac.rtspnative.videodecoder.VideoRenderClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Codec-specific glue between RTP depacketizer and MediaCodec decoder.
 * Keeps `RtspSession` blissfully ignorant of which video codec is on the
 * wire.
 */
internal interface VideoPipeline {
    val dimensions: StateFlow<Pair<Int, Int>?>

    /** Seed parameter sets from SDP fmtp before any RTP arrives. */
    fun seedFromSdp(track: TrackInfo.Video)

    /** Reassemble one RTP packet into zero or more access units. */
    fun depacketize(packet: RtpPacket): List<AccessUnit.Video>

    /** True once parameter sets are known. */
    fun canStart(): Boolean

    /** Configure + start the decoder, binding to [surface]. */
    fun start(surface: Surface)

    fun isStarted(): Boolean

    fun feed(au: AccessUnit.Video)

    fun replaceSurface(surface: Surface)

    /**
     * Stop rendering. Releases the underlying MediaCodec so the next
     * [resumeVideo] starts from a clean slate — this is significantly
     * more reliable across devices than trying to keep the codec alive
     * across a Surface destruction (some vendor decoders enter an
     * unrecoverable state when the bound Surface goes away).
     * Idempotent.
     */
    fun pauseVideo()

    /**
     * Resume after [pauseVideo]. Reconfigures the decoder against the
     * surface most recently passed to [start] or [replaceSurface] and
     * the latest SPS/PPS from the depacketizer, then drops every
     * inbound AU until the next IDR keyframe so the first frame fed
     * is a self-contained random-access point. Expect black until the
     * camera emits its next keyframe (typically <1 GOP).
     */
    fun resumeVideo()

    fun release()

    /** Cumulative count of access units dropped because the decoder's input queue was full. */
    val framesDropped: Long

    /**
     * Current parameter sets, sourced from SDP seed and/or in-band
     * extraction by the depacketizer. Null until the depacketizer has
     * seen them. By the time the session reaches Playing, this is
     * populated (else the decoder couldn't have started).
     */
    fun parameterSets(): VideoParameterSetsSnapshot?
}

internal data class VideoParameterSetsSnapshot(
    val vps: ByteArray? = null,
    val sps: ByteArray,
    val pps: ByteArray,
)

/**
 * Drops access units after the decoder has been (re)started until the
 * next IDR keyframe.
 *
 * Why: a freshly configured MediaCodec must be primed with a
 * self-contained random-access point. Feeding a P/B frame against
 * an empty reference buffer either errors or renders garbage. Cameras
 * emit keyframes on their GOP cadence, so we just wait for the next one.
 */
internal class IdrWaitGate {
    @Volatile var awaitingKeyframe: Boolean = false
        private set

    fun arm() { awaitingKeyframe = true }

    /** Returns true if [isKeyframe] AU should be passed to the decoder. */
    fun shouldPass(isKeyframe: Boolean): Boolean {
        if (!awaitingKeyframe) return true
        if (!isKeyframe) return false
        awaitingKeyframe = false
        return true
    }
}

internal class H264Pipeline(
    private val scope: CoroutineScope,
    private val renderClock: VideoRenderClock? = null,
) : VideoPipeline {
    private val depacketizer = H264Depacketizer()
    private var decoder: H264VideoDecoder? = null
    private var started = false
    @Volatile private var lastKnownSurface: Surface? = null
    private val idrGate = IdrWaitGate()
    private val _dimensions = MutableStateFlow<Pair<Int, Int>?>(null)
    override val dimensions: StateFlow<Pair<Int, Int>?> = _dimensions

    override fun seedFromSdp(track: TrackInfo.Video) {
        val sps = track.sps
        val pps = track.pps
        if (sps != null && pps != null) depacketizer.seedParameterSets(sps, pps)
    }

    override fun depacketize(packet: RtpPacket) = depacketizer.depacketize(packet)

    override fun canStart(): Boolean {
        val p = depacketizer.parameterSets
        return p?.sps != null && p.pps != null
    }

    override fun start(surface: Surface) {
        if (started) return
        val p = depacketizer.parameterSets ?: return
        val sps = p.sps ?: return
        val pps = p.pps ?: return
        lastKnownSurface = surface
        // A freshly configured codec needs a sync frame before it can
        // decode anything else; arm even on initial start so cameras
        // that don't sync PLAY to a GOP boundary don't feed garbage.
        idrGate.arm()
        val d = H264VideoDecoder(scope, renderClock)
        d.start(sps, pps, surface)
        decoder = d
        started = true
        scope.launchCollect(d.dimensions) { dims ->
            dims?.let { _dimensions.value = it.width to it.height }
        }
    }

    override fun isStarted() = started

    override fun feed(au: AccessUnit.Video) {
        if (!idrGate.shouldPass(au.isKeyframe)) return
        decoder?.feed(au)
    }

    override fun replaceSurface(surface: Surface) {
        lastKnownSurface = surface
        decoder?.replaceSurface(surface)
    }

    override fun pauseVideo() {
        decoder?.release()
        decoder = null
        started = false
    }

    override fun resumeVideo() {
        if (started) return
        val surface = lastKnownSurface ?: return
        if (!canStart()) return
        start(surface)
    }

    override fun release() {
        decoder?.release()
        decoder = null
        started = false
    }

    override val framesDropped: Long get() = decoder?.framesDropped ?: 0L

    override fun parameterSets(): VideoParameterSetsSnapshot? {
        val p = depacketizer.parameterSets ?: return null
        val sps = p.sps ?: return null
        val pps = p.pps ?: return null
        return VideoParameterSetsSnapshot(sps = sps, pps = pps)
    }
}

internal class H265Pipeline(
    private val scope: CoroutineScope,
    private val renderClock: VideoRenderClock? = null,
) : VideoPipeline {
    private val depacketizer = H265Depacketizer()
    private var decoder: H265VideoDecoder? = null
    private var started = false
    @Volatile private var lastKnownSurface: Surface? = null
    private val idrGate = IdrWaitGate()
    private val _dimensions = MutableStateFlow<Pair<Int, Int>?>(null)
    override val dimensions: StateFlow<Pair<Int, Int>?> = _dimensions

    override fun seedFromSdp(track: TrackInfo.Video) {
        if (track.vps != null || track.sps != null || track.pps != null) {
            depacketizer.seedParameterSets(track.vps, track.sps, track.pps)
        }
    }

    override fun depacketize(packet: RtpPacket) = depacketizer.depacketize(packet)

    override fun canStart(): Boolean {
        val p = depacketizer.parameterSets
        return p?.vps != null && p.sps != null && p.pps != null
    }

    override fun start(surface: Surface) {
        if (started) return
        val p = depacketizer.parameterSets ?: return
        val vps = p.vps ?: return
        val sps = p.sps ?: return
        val pps = p.pps ?: return
        lastKnownSurface = surface
        idrGate.arm()
        val d = H265VideoDecoder(scope, renderClock)
        d.start(vps, sps, pps, surface)
        decoder = d
        started = true
        scope.launchCollect(d.dimensions) { dims ->
            dims?.let { _dimensions.value = it.width to it.height }
        }
    }

    override fun isStarted() = started

    override fun feed(au: AccessUnit.Video) {
        if (!idrGate.shouldPass(au.isKeyframe)) return
        decoder?.feed(au)
    }

    override fun replaceSurface(surface: Surface) {
        lastKnownSurface = surface
        decoder?.replaceSurface(surface)
    }

    override fun pauseVideo() {
        decoder?.release()
        decoder = null
        started = false
    }

    override fun resumeVideo() {
        if (started) return
        val surface = lastKnownSurface ?: return
        if (!canStart()) return
        start(surface)
    }

    override fun release() {
        decoder?.release()
        decoder = null
        started = false
    }

    override val framesDropped: Long get() = decoder?.framesDropped ?: 0L

    override fun parameterSets(): VideoParameterSetsSnapshot? {
        val p = depacketizer.parameterSets ?: return null
        val vps = p.vps ?: return null
        val sps = p.sps ?: return null
        val pps = p.pps ?: return null
        return VideoParameterSetsSnapshot(vps = vps, sps = sps, pps = pps)
    }
}

private fun <T> CoroutineScope.launchCollect(
    flow: StateFlow<T>,
    block: suspend (T) -> Unit,
) {
    launch { flow.collect { block(it) } }
}
