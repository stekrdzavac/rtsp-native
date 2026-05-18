package com.skrdzavac.rtspnative.clocksync

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClockSyncTest {

    @Test
    fun `first timestamp anchors media time at zero`() {
        val c = ClockSync(clockRateHz = 90_000)
        assertEquals(0L, c.mediaTimeForRtp(123_000L))
    }

    @Test
    fun `subsequent timestamps produce monotonic media time`() {
        val c = ClockSync(clockRateHz = 90_000)
        c.mediaTimeForRtp(0L)
        val t1 = c.mediaTimeForRtp(3_000L) // 3000 ticks at 90 kHz = ~33ms
        val t2 = c.mediaTimeForRtp(6_000L) // 6000 ticks = ~66ms
        assertTrue(t1 in 33_000_000L..34_000_000L)
        assertTrue(t2 in 66_000_000L..67_000_000L)
    }
}
