// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.videodecoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.skrdzavac.rtspnative.core.AccessUnit
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
    private val paused = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)

    /** See [H264VideoDecoder.onError]. Set before [start]. */
    @Volatile
    var onError: (() -> Unit)? = null

    private var codec: MediaCodec? = null

    private val _dimensions = MutableStateFlow<SpsParser.Dimensions?>(null)
    val dimensions: StateFlow<SpsParser.Dimensions?> = _dimensions

    private val _framesDropped = AtomicLong(0)
    val framesDropped: Long get() = _framesDropped.get()

    private val pacingLogged = AtomicBoolean(false)

    fun start(
        vpsNal: ByteArray,
        spsNal: ByteArray,
        ppsNal: ByteArray,
        surface: Surface,
        preferSoftware: Boolean = false,
    ) {
        if (!started.compareAndSet(false, true)) return

        // Configure with the real coded size from the SPS. Decoders without
        // dynamic output buffers (e.g. OMX.sprd.hevc.decoder on Unisoc) size
        // their surface buffers statically from these dimensions and fault
        // before the first frame if they're too small for the stream. The
        // exact display size is refined later via OUTPUT_FORMAT_CHANGED.
        val dims = HevcSpsParser.parse(spsNal) ?: SpsParser.Dimensions(width = 1920, height = 1080)
        _dimensions.value = dims
        Log.i(TAG, "configuring HEVC decoder ${dims.width}x${dims.height}")

        try {
            val c = VideoDecoderFactory.createForSize(
                MediaFormat.MIMETYPE_VIDEO_HEVC, dims.width, dims.height, preferSoftware,
            )
            val csd0 = byteArrayOf(0, 0, 0, 1) + vpsNal +
                byteArrayOf(0, 0, 0, 1) + spsNal +
                byteArrayOf(0, 0, 0, 1) + ppsNal
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, dims.width, dims.height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            }
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            scope.launch(Dispatchers.IO) { inputLoop(c) }
            scope.launch(Dispatchers.IO) { outputLoop(c) }
            Log.i(TAG, "HEVC decoder started")
        } catch (t: Throwable) {
            // configure()/createByCodecName can throw (e.g. a surface-connect
            // race or a decoder that rejects the size). Route through the same
            // bounded recovery rather than letting it crash the session scope.
            Log.w(TAG, "decoder start failed: ${t.message}")
            runCatching { codec?.release() }
            codec = null
            notifyError()
        }
    }

    fun replaceSurface(surface: Surface) {
        // setOutputSurface is only valid while the codec is Executing; a codec
        // that has errored/released throws. Swallow it — the caller is a UI
        // layout callback that must not crash, and a dead codec gets rebuilt
        // on the next start().
        runCatching { codec?.setOutputSurface(surface) }
            .onFailure { Log.w(TAG, "replaceSurface ignored: ${it.message}") }
    }

    /** See [H264VideoDecoder.pause]. */
    fun pause() {
        paused.set(true)
    }

    /** See [H264VideoDecoder.resume]. */
    fun resume() {
        if (released.get()) return
        runCatching { codec?.flush() }
        paused.set(false)
    }

    fun feed(au: AccessUnit.Video) {
        if (released.get() || failed.get()) return
        if (paused.get()) return
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
            notifyError()
        }
    }

    private fun outputLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (!released.get()) {
                val index = codec.dequeueOutputBuffer(info, 10_000L)
                if (index >= 0) {
                    if (paused.get()) {
                        codec.releaseOutputBuffer(index, false)
                    } else {
                        val renderAt = renderClock?.systemTimeNsForVideoRtp(info.presentationTimeUs)
                        if (renderAt != null) {
                            if (pacingLogged.compareAndSet(false, true)) {
                                Log.i(TAG, "video pacing engaged")
                            }
                            codec.releaseOutputBuffer(index, renderAt)
                        } else {
                            codec.releaseOutputBuffer(index, true)
                        }
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
            notifyError()
        }
    }

    /** Fire [onError] once for an unexpected death (not a deliberate release). */
    private fun notifyError() {
        if (!released.get() && failed.compareAndSet(false, true)) {
            onError?.invoke()
        }
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    companion object {
        private const val TAG = "H265VideoDecoder"
    }
}
