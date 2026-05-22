// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.audiorendering

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.skrdzavac.rtspnative.audiodecoder.AudioPcmSink
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
 *
 * Pass an [AudioPresentationAnchor] to participate in A/V sync — the
 * renderer calls it on every non-muted PCM write until the anchor
 * reports success (typically the second or third block, once the audio
 * RTCP SR has landed). AvSyncClock uses that call as the presentation
 * origin.
 */
class RtspAudioRenderer(
    private val presentationAnchor: AudioPresentationAnchor? = null,
) : AudioPcmSink {

    @Volatile var isMuted: Boolean = false
    @Volatile var volume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            track?.setVolume(field)
        }

    @Volatile private var paused: Boolean = false

    private val released = AtomicBoolean(false)
    private val anchored = AtomicBoolean(false)
    private var track: AudioTrack? = null
    private var currentSampleRate: Int = 0
    private var currentChannels: Int = 0

    override fun onPcm(pcm: ShortArray, sampleRateHz: Int, channels: Int, rtpTs: Long) {
        if (released.get()) return
        if (isMuted) return
        if (paused) return
        val t = ensureTrack(sampleRateHz, channels) ?: return
        if (!anchored.get()) {
            // First audio PCM block often arrives before the camera's
            // first RTCP SR, so the anchor can fail and must be retried.
            // Latch only once it actually succeeds; otherwise the
            // presentation timeline stays unset and video renders unpaced.
            val ok = presentationAnchor?.onFirstPcmSubmitted(rtpTs, System.nanoTime()) ?: true
            if (ok) anchored.set(true)
        }
        try {
            t.write(pcm, 0, pcm.size)
        } catch (e: Throwable) {
            Log.w(TAG, "AudioTrack.write failed: ${e.message}")
        }
    }

    /**
     * Halt audio playback. Pending PCM in the AudioTrack buffer is kept;
     * incoming PCM from [onPcm] is dropped while paused so the buffer
     * doesn't block on write. Safe before the first frame has arrived.
     */
    @Synchronized
    fun pause() {
        if (released.get()) return
        paused = true
        runCatching { track?.pause() }
    }

    /**
     * Resume after [pause]. Discards the AudioTrack's stale buffer
     * (otherwise resume would play back audio from however long ago we
     * paused) and restarts playback. Safe before the track exists.
     */
    @Synchronized
    fun resume() {
        if (released.get()) return
        runCatching { track?.flush() }
        runCatching { track?.play() }
        paused = false
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
