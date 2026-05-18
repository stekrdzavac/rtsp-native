package com.nittbit.rtspkit.audiodecoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.nittbit.rtspkit.core.AccessUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AAC hardware decoder backed by `MediaCodec(audio/mp4a-latm)`. Input is
 * raw AAC AUs (from [com.nittbit.rtspkit.audiodepacketizer.AacDepacketizer]);
 * output is signed 16-bit little-endian PCM, delivered to [sink].
 *
 * `csd-0` is the SDP-provided `AudioSpecificConfig` (typically 2 bytes,
 * e.g. 0x12 0x10 = AAC-LC, 44.1 kHz, mono).
 */
class AacAudioDecoder(
    private val scope: CoroutineScope,
    private val sink: AudioPcmSink,
) {
    private val inputs = Channel<AccessUnit.Audio>(capacity = 64)
    private val released = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    private var codec: MediaCodec? = null
    private var sampleRateHz: Int = 0
    private var channels: Int = 1

    fun start(audioSpecificConfig: ByteArray, sampleRateHz: Int, channels: Int) {
        if (!started.compareAndSet(false, true)) return
        this.sampleRateHz = sampleRateHz
        this.channels = channels
        Log.i(TAG, "configuring AAC decoder ${sampleRateHz}Hz/${channels}ch (csd=${audioSpecificConfig.size}B)")

        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRateHz, channels).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(audioSpecificConfig))
        }
        c.configure(format, null, null, 0)
        c.start()
        codec = c
        scope.launch(Dispatchers.IO) { inputLoop(c) }
        scope.launch(Dispatchers.IO) { outputLoop(c) }
    }

    fun feed(au: AccessUnit.Audio) {
        if (released.get()) return
        inputs.trySend(au)
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
                val ptsUs = if (sampleRateHz > 0) (au.ptsRtp * 1_000_000L) / sampleRateHz else au.ptsRtp
                codec.queueInputBuffer(index, 0, au.payload.size, ptsUs, 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AAC input loop ended: ${t.message}")
        }
    }

    private fun outputLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (!released.get()) {
                val index = codec.dequeueOutputBuffer(info, 10_000L)
                if (index >= 0) {
                    val out = codec.getOutputBuffer(index)
                    if (out != null && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val pcm = ShortArray(info.size / 2)
                        out.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().get(pcm)
                        sink.onPcm(pcm, sampleRateHz, channels)
                    }
                    codec.releaseOutputBuffer(index, false)
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    val w = newFormat.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE)
                    val c = newFormat.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT)
                    if (w != null) sampleRateHz = w
                    if (c != null) channels = c
                    Log.i(TAG, "AAC output format changed: ${sampleRateHz}Hz/${channels}ch")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AAC output loop ended: ${t.message}")
        }
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    companion object {
        private const val TAG = "AacAudioDecoder"
    }
}
