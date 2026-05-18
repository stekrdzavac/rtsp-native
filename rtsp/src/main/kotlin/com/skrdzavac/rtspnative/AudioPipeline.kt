package com.skrdzavac.rtspnative

import com.skrdzavac.rtspnative.audiodecoder.AacAudioDecoder
import com.skrdzavac.rtspnative.audiodecoder.G711Decoder
import com.skrdzavac.rtspnative.audiodecoder.L16Decoder
import com.skrdzavac.rtspnative.audiorendering.RtspAudioRenderer
import com.skrdzavac.rtspnative.audiodepacketizer.AacDepacketizer
import com.skrdzavac.rtspnative.audiodepacketizer.AudioDepacketizer
import com.skrdzavac.rtspnative.audiodepacketizer.G711Depacketizer
import com.skrdzavac.rtspnative.audiodepacketizer.L16Depacketizer
import com.skrdzavac.rtspnative.core.AccessUnit
import com.skrdzavac.rtspnative.core.AudioCodec
import com.skrdzavac.rtspnative.core.RtpPacket
import com.skrdzavac.rtspnative.signaling.TrackInfo
import kotlinx.coroutines.CoroutineScope

internal interface AudioPipeline {
    fun depacketize(packet: RtpPacket): List<AccessUnit.Audio>
    fun canStart(): Boolean
    fun start()
    fun feed(au: AccessUnit.Audio)
    fun release()
    fun isStarted(): Boolean
    /** Cumulative count of access units dropped because the decoder's input queue was full. */
    val framesDropped: Long get() = 0L
}

internal class AacPipeline(
    private val scope: CoroutineScope,
    private val track: TrackInfo.Audio,
    private val renderer: RtspAudioRenderer,
) : AudioPipeline {
    private val depacketizer = AacDepacketizer(
        sizeLength = track.sizeLength,
        indexDeltaLength = track.indexDeltaLength,
    )
    private var decoder: AacAudioDecoder? = null
    private var started = false

    override fun depacketize(packet: RtpPacket) = depacketizer.depacketize(packet)

    override fun canStart() = track.configBytes != null

    override fun start() {
        if (started) return
        val csd = track.configBytes ?: return
        val d = AacAudioDecoder(scope, renderer)
        d.start(csd, track.clockRate, track.channels)
        decoder = d
        started = true
    }

    override fun isStarted() = started

    override fun feed(au: AccessUnit.Audio) { decoder?.feed(au) }

    override fun release() { decoder?.release() }

    override val framesDropped: Long get() = decoder?.framesDropped ?: 0L
}

/** PCMU / PCMA — pure-Kotlin lookup-table decode, no MediaCodec. */
internal class G711Pipeline(
    private val track: TrackInfo.Audio,
    private val renderer: RtspAudioRenderer,
) : AudioPipeline {
    private val depacketizer: AudioDepacketizer = G711Depacketizer()
    private var started = false

    override fun depacketize(packet: RtpPacket) = depacketizer.depacketize(packet)

    override fun canStart() = true

    override fun start() { started = true }

    override fun isStarted() = started

    override fun feed(au: AccessUnit.Audio) {
        if (!started) return
        val pcm = when (track.codec) {
            AudioCodec.PCMU -> G711Decoder.decodeUlaw(au.payload)
            AudioCodec.PCMA -> G711Decoder.decodeAlaw(au.payload)
            else -> return
        }
        renderer.onPcm(pcm, track.clockRate, track.channels, rtpTs = au.ptsRtp)
    }

    override fun release() {}
}

/** L16 — 16-bit big-endian PCM. Byte-swap and forward to AudioTrack. */
internal class L16Pipeline(
    private val track: TrackInfo.Audio,
    private val renderer: RtspAudioRenderer,
) : AudioPipeline {
    private val depacketizer: AudioDepacketizer = L16Depacketizer()
    private var started = false

    override fun depacketize(packet: RtpPacket) = depacketizer.depacketize(packet)

    override fun canStart() = true

    override fun start() { started = true }

    override fun isStarted() = started

    override fun feed(au: AccessUnit.Audio) {
        if (!started) return
        val pcm = L16Decoder.toLittleEndianPcm(au.payload)
        renderer.onPcm(pcm, track.clockRate, track.channels, rtpTs = au.ptsRtp)
    }

    override fun release() {}
}
