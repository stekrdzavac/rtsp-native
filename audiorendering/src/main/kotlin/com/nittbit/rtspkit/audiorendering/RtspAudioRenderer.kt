package com.nittbit.rtspkit.audiorendering

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.nittbit.rtspkit.audiodecoder.AudioPcmSink
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps [AudioTrack] in streaming mode. Acts as an [AudioPcmSink] so a
 * decoder can shovel PCM samples in without knowing about the platform
 * audio plumbing.
 *
 * The track is created lazily on the first PCM frame (sample rate and
 * channel count are only known after decode begins). If the format ever
 * changes mid-stream the track is recreated.
 *
 * [volume] in [0f, 1f]; [isMuted] short-circuits writes without disturbing
 * the underlying track.
 */
class RtspAudioRenderer : AudioPcmSink {

    @Volatile var isMuted: Boolean = false
    @Volatile var volume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            track?.setVolume(field)
        }

    private val released = AtomicBoolean(false)
    private var track: AudioTrack? = null
    private var currentSampleRate: Int = 0
    private var currentChannels: Int = 0

    override fun onPcm(pcm: ShortArray, sampleRateHz: Int, channels: Int) {
        if (released.get()) return
        if (isMuted) return
        val t = ensureTrack(sampleRateHz, channels) ?: return
        try {
            t.write(pcm, 0, pcm.size)
        } catch (e: Throwable) {
            Log.w(TAG, "AudioTrack.write failed: ${e.message}")
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
    }

    @Synchronized
    private fun ensureTrack(sampleRateHz: Int, channels: Int): AudioTrack? {
        if (track != null && sampleRateHz == currentSampleRate && channels == currentChannels) {
            return track
        }
        // Recreate on format change.
        runCatching { track?.release() }
        track = null

        val channelConfig = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> {
                Log.w(TAG, "unsupported channel count: $channels")
                return null
            }
        }
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioTrack.getMinBufferSize(sampleRateHz, channelConfig, encoding)
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioTrack.getMinBufferSize failed for ${sampleRateHz}Hz/${channels}ch")
            return null
        }
        val bufferBytes = minBuffer * 4  // generous; reduces underruns

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(channelConfig)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(volume)
        t.play()
        currentSampleRate = sampleRateHz
        currentChannels = channels
        track = t
        Log.i(TAG, "AudioTrack created: ${sampleRateHz}Hz/${channels}ch buf=${bufferBytes}B")
        return t
    }

    companion object {
        private const val TAG = "RtspAudioRenderer"
    }
}
