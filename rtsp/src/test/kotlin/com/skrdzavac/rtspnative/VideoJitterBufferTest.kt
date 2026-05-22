// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative

import com.skrdzavac.rtspnative.core.AccessUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VideoJitterBufferTest {

    /** RTP ticks for one frame at 24 fps in a 90 kHz clock. */
    private val frameTicks = 90_000L / 24L

    private fun videoAu(rtpTs: Long, isKeyframe: Boolean = false) =
        AccessUnit.Video(ptsRtp = rtpTs, payload = byteArrayOf(0), isKeyframe = isKeyframe)

    /**
     * Build a buffer whose [nanoTime] is bound to the test scheduler's
     * virtual clock — `delay(ms)` then advances both the dispatcher and the
     * clock returned to the buffer, so timing assertions are deterministic.
     */
    private fun TestScope.newBuffer(
        bufferDelayMs: Int = 100,
        capacity: Int = 8,
        output: (AccessUnit.Video) -> Unit,
    ): VideoJitterBuffer = VideoJitterBuffer(
        scope = this,
        bufferDelayMs = bufferDelayMs,
        output = output,
        capacity = capacity,
        nanoTime = { testScheduler.currentTime * 1_000_000L },
    )

    @Test
    fun `first AU is released after the configured startup delay`() = runTest {
        val released = mutableListOf<Pair<Long, AccessUnit.Video>>()
        val buf = newBuffer(bufferDelayMs = 100) { au ->
            released += testScheduler.currentTime to au
        }
        buf.start()

        val au = videoAu(rtpTs = 1_000_000L, isKeyframe = true)
        buf.submit(au)

        // Before the delay elapses, nothing must have been released.
        advanceTimeBy(99)
        runCurrent()
        assertTrue(released.isEmpty(), "released too early: $released")

        // Just past the delay, the AU should land.
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, released.size)
        assertEquals(au, released[0].second)
        assertEquals(100L, released[0].first)

        buf.close()
    }

    @Test
    fun `subsequent AUs are released at steady RTP-paced cadence`() = runTest {
        val released = mutableListOf<Pair<Long, Long>>()  // (virtualMs, ptsRtp)
        val buf = newBuffer(bufferDelayMs = 100) { au ->
            released += testScheduler.currentTime to au.ptsRtp
        }
        buf.start()

        // Three frames arrive in a burst (network jitter scenario).
        buf.submit(videoAu(rtpTs = 0L, isKeyframe = true))
        buf.submit(videoAu(rtpTs = frameTicks))
        buf.submit(videoAu(rtpTs = 2 * frameTicks))

        advanceUntilIdle()

        // Despite the bursty arrival, releases should be paced at ~41.67ms.
        assertEquals(3, released.size)
        assertEquals(100L, released[0].first)
        // Allow ±1ms rounding from ms-grained delay.
        assertTrue(released[1].first in 140L..142L, "frame 1 at ${released[1].first}")
        assertTrue(released[2].first in 182L..184L, "frame 2 at ${released[2].first}")

        buf.close()
    }

    @Test
    fun `late AU is released immediately without further wait`() = runTest {
        val released = mutableListOf<Pair<Long, Long>>()
        val buf = newBuffer(bufferDelayMs = 50) { au ->
            released += testScheduler.currentTime to au.ptsRtp
        }
        buf.start()

        // First frame anchors. Released at virtual time = 50ms.
        buf.submit(videoAu(rtpTs = 0L, isKeyframe = true))
        advanceUntilIdle()
        assertEquals(50L, released[0].first)

        // Skip a long time — much later than the second frame's target.
        // When it finally arrives, drain should release it immediately.
        advanceTimeBy(5_000)
        runCurrent()
        buf.submit(videoAu(rtpTs = frameTicks))
        advanceUntilIdle()

        assertEquals(2, released.size)
        // Second release time should be the current virtual time (~5050ms),
        // NOT anchor + 41.67ms. We don't synthesize a wait into the past.
        assertTrue(released[1].first >= 5_050L, "late AU should release immediately, got ${released[1].first}")

        buf.close()
    }

    @Test
    fun `submit returns false and counts drops when capacity is exceeded`() = runTest {
        val output: (AccessUnit.Video) -> Unit = { /* never drained */ }
        val buf = newBuffer(bufferDelayMs = 100, capacity = 2, output = output)
        buf.start()

        // First two fit; drain loop is blocked on its startup delay so the
        // queue actually holds them.
        assertTrue(buf.submit(videoAu(rtpTs = 0L, isKeyframe = true)))
        assertTrue(buf.submit(videoAu(rtpTs = frameTicks)))
        // Third overflows the capacity.
        assertEquals(false, buf.submit(videoAu(rtpTs = 2 * frameTicks)))
        assertEquals(1L, buf.framesDropped)

        buf.close()
    }

    @Test
    fun `clamps schedule when accumulated drift pushes target past max-ahead`() = runTest {
        val released = mutableListOf<Pair<Long, Long>>()
        val buf = newBuffer(bufferDelayMs = 100) { au ->
            released += testScheduler.currentTime to au.ptsRtp
        }
        buf.start()

        // First frame anchors at virtual time 100.
        buf.submit(videoAu(rtpTs = 0L, isKeyframe = true))
        advanceUntilIdle()
        assertEquals(100L, released[0].first)
        assertEquals(0L, buf.driftCorrections)

        // Second frame's RTP timestamp implies the schedule would render it
        // 10 seconds in the future (simulates accumulated camera/device
        // clock drift after a long-running session). Without correction the
        // drain would block for ~10 s and the channel would fill behind it.
        buf.submit(videoAu(rtpTs = 900_000L))
        advanceUntilIdle()

        assertEquals(2, released.size)
        // Clamped to max-ahead = bufferDelay * 2 = 200ms from the moment the
        // second frame was drainable (virtual time 100), so release at 300.
        assertEquals(300L, released[1].first)
        assertEquals(1L, buf.driftCorrections)

        // Subsequent frames at normal RTP cadence should pace correctly
        // off the slid anchor — no further corrections needed.
        buf.submit(videoAu(rtpTs = 900_000L + frameTicks))
        advanceUntilIdle()
        assertEquals(3, released.size)
        // ~41.67ms after previous release → ms-grained delay lands at 341.
        assertTrue(released[2].first in 340L..342L, "frame at ${released[2].first}")
        assertEquals(1L, buf.driftCorrections)

        buf.close()
    }

    @Test
    fun `no drift correction during normal paced operation`() = runTest {
        val buf = newBuffer(bufferDelayMs = 100) { /* drain */ }
        buf.start()

        repeat(50) { i ->
            buf.submit(videoAu(rtpTs = i.toLong() * frameTicks, isKeyframe = i == 0))
        }
        advanceUntilIdle()

        assertEquals(0L, buf.driftCorrections)
        buf.close()
    }

    @Test
    fun `close stops the drain loop`() = runTest {
        var releaseCount = 0
        val buf = newBuffer(bufferDelayMs = 10) { releaseCount++ }
        buf.start()
        buf.submit(videoAu(rtpTs = 0L, isKeyframe = true))
        advanceUntilIdle()
        assertEquals(1, releaseCount)

        buf.close()
        buf.submit(videoAu(rtpTs = frameTicks))
        advanceUntilIdle()
        assertEquals(1, releaseCount, "no AUs should be released after close()")
    }
}
