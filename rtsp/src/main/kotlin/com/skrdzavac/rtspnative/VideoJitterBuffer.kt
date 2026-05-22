// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative

import android.util.Log
import com.skrdzavac.rtspnative.core.AccessUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Pre-decoder pacing buffer for video access units. AUs are submitted as
 * they arrive from the network and released to [output] (the decoder)
 * at a steady cadence derived from their RTP timestamps.
 *
 * Why this exists: a 4K H.265 stream on a healthy LAN can still have
 * tens of milliseconds of arrival jitter — a motion-heavy P-frame is
 * fragmented into many more RTP packets than an idle one and takes
 * longer to traverse the network. Feeding the decoder at the arrival
 * cadence translates that jitter directly into on-screen stutter.
 *
 * The buffer holds the first AU for [bufferDelayMs] before releasing
 * it; that delay doubles as a jitter buffer that absorbs late
 * arrivals. Once the timeline is anchored, every subsequent AU is
 * scheduled at `anchor + (rtpDelta / clockRateHz) * 1e9 ns`.
 *
 * An AU that arrives later than its scheduled time is released
 * immediately — we never insert a negative delay.
 *
 * Drift correction: the camera's RTP clock and the device's
 * `System.nanoTime` are independent quartz oscillators. Typical
 * relative drift of 20–100 ppm accumulates over a long-running stream
 * (e.g. ~900 ms over 30 minutes at 50 ppm). When the schedule's
 * `targetNs` would land more than `bufferDelayMs * 2` ahead of the
 * current wall clock, the anchor is slid back by the excess — that
 * bounds latency to `bufferDelayMs * 2` forever without abrupt jumps,
 * and the next frame's normal RTP delta resumes regular cadence from
 * the slid anchor.
 *
 * Unrelated to A/V sync: this is purely an *input-side* pacing fix,
 * complementary to (and decoupled from) the renderClock-driven output
 * pacing in the MediaCodec wrapper.
 */
internal class VideoJitterBuffer(
    private val scope: CoroutineScope,
    bufferDelayMs: Int,
    private val output: (AccessUnit.Video) -> Unit,
    capacity: Int = DEFAULT_CAPACITY,
    private val clockRateHz: Long = VIDEO_CLOCK_RATE_HZ,
    private val nanoTime: () -> Long = System::nanoTime,
) {

    private val bufferDelayNs: Long = bufferDelayMs.toLong() * 1_000_000L
    private val maxAheadNs: Long = bufferDelayNs * 2L
    private val inputs = Channel<AccessUnit.Video>(capacity = capacity)
    private val released = AtomicBoolean(false)
    private val _framesDropped = AtomicLong(0L)
    private val _driftCorrections = AtomicLong(0L)
    private val driftLogged = AtomicBoolean(false)

    @Volatile private var anchorSysNs: Long = Long.MIN_VALUE
    @Volatile private var anchorRtpTs: Long = 0L
    private var job: Job? = null

    val framesDropped: Long get() = _framesDropped.get()
    val driftCorrections: Long get() = _driftCorrections.get()

    fun start() {
        if (job != null) return
        job = scope.launch { drainLoop() }
    }

    /** Returns true if accepted, false if the buffer is full. */
    fun submit(au: AccessUnit.Video): Boolean {
        if (released.get()) return false
        val result = inputs.trySend(au)
        return if (result.isSuccess) {
            true
        } else {
            _framesDropped.incrementAndGet()
            false
        }
    }

    fun close() {
        if (!released.compareAndSet(false, true)) return
        inputs.close()
        job?.cancel()
        job = null
    }

    private suspend fun drainLoop() {
        try {
            for (au in inputs) {
                if (released.get()) break
                if (anchorSysNs == Long.MIN_VALUE) {
                    anchorSysNs = nanoTime() + bufferDelayNs
                    anchorRtpTs = au.ptsRtp
                }
                var targetNs = anchorSysNs +
                    (au.ptsRtp - anchorRtpTs) * 1_000_000_000L / clockRateHz
                val now = nanoTime()
                val maxTargetNs = now + maxAheadNs
                if (targetNs > maxTargetNs) {
                    val excessNs = targetNs - maxTargetNs
                    anchorSysNs -= excessNs
                    targetNs = maxTargetNs
                    _driftCorrections.incrementAndGet()
                    if (driftLogged.compareAndSet(false, true)) {
                        Log.i(TAG, "drift correction engaged (buffer=${bufferDelayNs / 1_000_000}ms)")
                    }
                }
                val waitNs = targetNs - now
                if (waitNs > 0) {
                    delay(waitNs / 1_000_000L)
                }
                output(au)
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w(TAG, "drain loop ended: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "VideoJitterBuffer"
        private const val DEFAULT_CAPACITY = 64

        // H.264 (RFC 3984) and H.265 (RFC 7798) both define a 90 kHz RTP
        // clock. SDP almost always confirms this; not currently plumbed
        // through because it's never been observed to differ in practice.
        private const val VIDEO_CLOCK_RATE_HZ: Long = 90_000L
    }
}
