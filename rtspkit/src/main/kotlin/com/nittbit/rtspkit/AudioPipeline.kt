package com.nittbit.rtspkit

import com.nittbit.rtspkit.audiodecoder.AacAudioDecoder
import com.nittbit.rtspkit.audiodecoder.G711Decoder
import com.nittbit.rtspkit.audiodecoder.L16Decoder
import com.nittbit.rtspkit.audiorendering.RtspAudioRenderer
import com.nittbit.rtspkit.audiodepacketizer.AacDepacketizer
import com.nittbit.rtspkit.audiodepacketizer.AudioDepacketizer
import com.nittbit.rtspkit.audiodepacketizer.G711Depacketizer
import com.nittbit.rtspkit.audiodepacketizer.L16Depacketizer
import com.nittbit.rtspkit.core.AccessUnit
import com.nittbit.rtspkit.core.AudioCodec
import com.nittbit.rtspkit.core.RtpPacket
import com.nittbit.rtspkit.signaling.TrackInfo
import kotlinx.coroutines.CoroutineScope

internal interface AudioPipeline {
    fun depacketize(packet: RtpPacket): List<AccessUnit.Audio>
    fun canStart(): Boolean
    fun start()
    fun feed(au: AccessUnit.Audio)
    fun release()
    fun isStarted(): Boolean
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
