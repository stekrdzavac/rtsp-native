package com.nittbit.rtspkit.videodecoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.nittbit.rtspkit.core.AccessUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * HEVC (H.265) hardware decoder backed by [MediaCodec]. Same lifecycle as
 * [H264VideoDecoder]: configure-and-start happens in one [start] call with
 * VPS+SPS+PPS and a Surface. The CSDs are concatenated Annex-B byte streams
 * in `csd-0`, which is what every modern Android MediaCodec accepts.
 */
class H265VideoDecoder(
    private val scope: CoroutineScope,
    private val renderClock: VideoRenderClock? = null,
) {
    private val inputs = Channel<AccessUnit.Video>(capacity = 64)
    private val released = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    private var codec: MediaCodec? = null

    private val _dimensions = MutableStateFlow<SpsParser.Dimensions?>(null)
    val dimensions: StateFlow<SpsParser.Dimensions?> = _dimensions

    private val _framesDropped = AtomicLong(0)
    val framesDropped: Long get() = _framesDropped.get()

    fun start(vpsNal: ByteArray, spsNal: ByteArray, ppsNal: ByteArray, surface: Surface) {
        if (!started.compareAndSet(false, true)) return

        // Best-effort initial dimensions. The real values come from the
        // MediaCodec OUTPUT_FORMAT_CHANGED callback once decoding starts —
        // parsing H.265 SPS is more involved than H.264 and we'd rather
        // let the decoder tell us.
        _dimensions.value = SpsParser.Dimensions(width = 1920, height = 1080)
        Log.i(TAG, "configuring HEVC decoder (initial 1920x1080; will refine on first OUTPUT_FORMAT_CHANGED)")

        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        val csd0 = byteArrayOf(0, 0, 0, 1) + vpsNal +
            byteArrayOf(0, 0, 0, 1) + spsNal +
            byteArrayOf(0, 0, 0, 1) + ppsNal
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        }
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        scope.launch(Dispatchers.IO) { inputLoop(c) }
        scope.launch(Dispatchers.IO) { outputLoop(c) }
        Log.i(TAG, "HEVC decoder started")
    }

    fun replaceSurface(surface: Surface) {
        codec?.setOutputSurface(surface)
    }

    fun feed(au: AccessUnit.Video) {
        if (released.get()) return
        val result = inputs.trySend(au)
        if (result.isFailure) {
            _framesDropped.incrementAndGet()
            Log.w(TAG, "input buffer full, dropping AU ts=${au.ptsRtp}")
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        inputs.close()
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private suspend fun inputLoop(codec: MediaCodec) {
        try {
            for (au in inputs) {
                if (released.get()) break
                val index = codec.dequeueInputBuffer(10_000L)
                if (index < 0) continue
                val buffer = codec.getInputBuffer(index) ?: continue
                buffer.clear()
                buffer.put(au.payload)
                codec.queueInputBuffer(index, 0, au.payload.size, au.ptsRtp, 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "input loop ended: ${t.message}")
        }
    }

    private fun outputLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (!released.get()) {
                val index = codec.dequeueOutputBuffer(info, 10_000L)
                if (index >= 0) {
                    val renderAt = renderClock?.systemTimeNsForVideoRtp(info.presentationTimeUs)
                    if (renderAt != null) {
                        codec.releaseOutputBuffer(index, renderAt)
                    } else {
                        codec.releaseOutputBuffer(index, true)
                    }
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    val w = newFormat.getIntegerOrNull(MediaFormat.KEY_WIDTH)
                    val h = newFormat.getIntegerOrNull(MediaFormat.KEY_HEIGHT)
                    if (w != null && h != null && w > 0 && h > 0) {
                        _dimensions.value = SpsParser.Dimensions(w, h)
                        Log.i(TAG, "HEVC output format changed: ${w}x${h}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "output loop ended: ${t.message}")
        }
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    companion object {
        private const val TAG = "H265VideoDecoder"
    }
}
