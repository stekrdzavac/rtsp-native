// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.skrdzavac.rtspnative.core.AccessUnit
import com.skrdzavac.rtspnative.core.AudioCodec
import com.skrdzavac.rtspnative.core.VideoCodec
import com.skrdzavac.rtspnative.signaling.TrackInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.ByteBuffer

/**
 * Writes the incoming H.264/H.265 (and optionally AAC) access units to
 * an MP4 file via [MediaMuxer]. No re-encode — the AUs from the
 * depacketizer are remuxed directly. AVCC/HVCC conversion happens
 * per-sample because MP4 wants length-prefixed NAL units instead of
 * Annex-B start codes.
 *
 * The recorder waits for the first IDR keyframe before writing any
 * video so the resulting MP4 starts at a decodable boundary. Audio is
 * gated on the first video keyframe so A/V starts roughly aligned.
 *
 * Stage 2 limitations:
 *   - Only AAC audio is muxed; G.711/L16 streams record video only.
 *   - Each track's PTS is anchored to its first AU's RTP timestamp.
 *     For tight A/V sync inside the MP4 we'd use the AvSyncClock's NTP
 *     anchors, but those are only available a few seconds in (after the
 *     first RTCP SR).
 *   - No timestamp unwrap; recordings approaching 13 hours can hit RTP
 *     wraparound and produce non-monotonic PTS.
 */
class RtspRecorder internal constructor(
    val file: File,
    private val videoCodec: VideoCodec,
    private val videoClockRate: Int,
    private val videoVps: ByteArray?,
    private val videoSps: ByteArray,
    private val videoPps: ByteArray,
    private val audioTrackInfo: TrackInfo.Audio?,
    width: Int,
    height: Int,
) {
    enum class State { Idle, Recording, Stopped, Failed }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state

    private val _bytesWritten = MutableStateFlow(0L)
    val bytesWritten: StateFlow<Long> = _bytesWritten

    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var firstKeyframeSeen = false
    private var firstVideoRtp: Long = -1
    private var firstAudioRtp: Long = -1

    private val videoFormat: MediaFormat = buildVideoFormat(width, height)
    private val audioFormat: MediaFormat? = buildAudioFormat()

    internal fun start() {
        if (_state.value != State.Idle) return
        try {
            val m = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoTrackIndex = m.addTrack(videoFormat)
            audioFormat?.let { audioTrackIndex = m.addTrack(it) }
            m.start()
            muxer = m
            _state.value = State.Recording
            Log.i(TAG, "recording started → ${file.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start recording", t)
            _state.value = State.Failed
        }
    }

    internal fun onVideoAu(au: AccessUnit.Video) {
        if (_state.value != State.Recording) return
        if (!firstKeyframeSeen) {
            if (!au.isKeyframe) return
            firstKeyframeSeen = true
            firstVideoRtp = au.ptsRtp
        }
        val m = muxer ?: return
        val avcc = AnnexBToAvcc.convert(au.payload)
        val info = MediaCodec.BufferInfo().apply {
            set(
                0,
                avcc.size,
                rtpToUs(au.ptsRtp, firstVideoRtp, videoClockRate),
                if (au.isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
            )
        }
        try {
            m.writeSampleData(videoTrackIndex, ByteBuffer.wrap(avcc), info)
            _bytesWritten.value += avcc.size
        } catch (t: Throwable) {
            Log.w(TAG, "video writeSampleData failed: ${t.message}")
            _state.value = State.Failed
        }
    }

    internal fun onAudioAu(au: AccessUnit.Audio) {
        if (_state.value != State.Recording) return
        if (audioTrackIndex < 0) return
        if (!firstKeyframeSeen) return  // wait for video to start so MP4 begins at video keyframe
        val m = muxer ?: return
        val audio = audioTrackInfo ?: return
        if (firstAudioRtp < 0) firstAudioRtp = au.ptsRtp
        val info = MediaCodec.BufferInfo().apply {
            set(
                0,
                au.payload.size,
                rtpToUs(au.ptsRtp, firstAudioRtp, audio.clockRate),
                0,
            )
        }
        try {
            m.writeSampleData(audioTrackIndex, ByteBuffer.wrap(au.payload), info)
            _bytesWritten.value += au.payload.size
        } catch (t: Throwable) {
            Log.w(TAG, "audio writeSampleData failed: ${t.message}")
        }
    }

    fun stop() {
        if (_state.value != State.Recording) return
        _state.value = State.Stopped
        runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        Log.i(TAG, "recording stopped → ${file.absolutePath} (${_bytesWritten.value}B)")
    }

    private fun buildVideoFormat(width: Int, height: Int): MediaFormat {
        val mime = when (videoCodec) {
            VideoCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            VideoCodec.H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
        }
        return MediaFormat.createVideoFormat(mime, width, height).apply {
            when (videoCodec) {
                VideoCodec.H264 -> {
                    setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + videoSps))
                    setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + videoPps))
                }
                VideoCodec.H265 -> {
                    val vps = videoVps
                        ?: error("HEVC recording requires VPS; depacketizer has not yet seen it in-band")
                    val csd0 = byteArrayOf(0, 0, 0, 1) + vps +
                        byteArrayOf(0, 0, 0, 1) + videoSps +
                        byteArrayOf(0, 0, 0, 1) + videoPps
                    setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                }
            }
        }
    }

    private fun buildAudioFormat(): MediaFormat? {
        val audio = audioTrackInfo ?: return null
        if (audio.codec != AudioCodec.AAC) return null  // Stage 2: only AAC into MP4
        val csd = audio.configBytes ?: return null
        return MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            audio.clockRate,
            audio.channels,
        ).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        }
    }

    private fun rtpToUs(rtpTs: Long, anchorRtp: Long, clockRate: Int): Long {
        val delta = rtpTs - anchorRtp
        return (delta * 1_000_000L) / clockRate.coerceAtLeast(1)
    }

    companion object {
        private const val TAG = "RtspRecorder"
    }
}
