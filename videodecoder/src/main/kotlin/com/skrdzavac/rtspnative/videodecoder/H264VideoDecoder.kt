// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.videodecoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.skrdzavac.rtspnative.core.AccessUnit
import com.skrdzavac.rtspnative.core.RtspError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * H.264 hardware decoder backed by [MediaCodec]. Input is Annex-B AUs from
 * the depacketizer; output is rendered directly onto the [Surface] passed
 * to [start] (zero-copy via MediaCodec's render path).
 *
 * Lifecycle:
 *   1. Construct.
 *   2. [start] with SPS + PPS + Surface — the codec is configured *with*
 *      the surface so the render path is established once and for all.
 *      MediaCodec.setOutputSurface can only swap surfaces on a codec that
 *      was originally configured with one, so we never configure without
 *      a Surface in the first place.
 *   3. [feed] AUs.
 *   4. [release] on stop / error.
 *
 * If the surface needs to change later, call [replaceSurface]. If the codec
 * was originally started without a surface, [replaceSurface] will throw —
 * by design.
 */
class H264VideoDecoder(
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

    /**
     * Build the codec configuration from SDP-derived or in-band SPS/PPS
     * (RAW NAL bytes, no Annex-B start code) and bind it to [surface].
     */
    fun start(spsNal: ByteArray, ppsNal: ByteArray, surface: Surface) {
        if (!started.compareAndSet(false, true)) return
        val dims = SpsParser.parse(spsNal) ?: SpsParser.Dimensions(width = 1920, height = 1080)
        _dimensions.value = dims
        Log.i(TAG, "configuring decoder ${dims.width}x${dims.height}")

        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, dims.width, dims.height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + spsNal))
            setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + ppsNal))
        }
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        scope.launch(Dispatchers.IO) { inputLoop(c) }
        scope.launch(Dispatchers.IO) { outputLoop(c) }
        Log.i(TAG, "decoder started")
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
                // Use the raw RTP timestamp as MediaCodec's presentationTimeUs
                // (it's just a pass-through tag). We recover it in the output
                // loop to ask AvSyncClock when to render.
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
                        Log.i(TAG, "output format changed: ${w}x${h}")
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
        private const val TAG = "H264VideoDecoder"
    }
}
