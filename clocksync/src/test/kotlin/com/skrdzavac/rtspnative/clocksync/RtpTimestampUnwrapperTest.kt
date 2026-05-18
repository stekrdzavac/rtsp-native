package com.skrdzavac.rtspnative.clocksync

import org.junit.Test
import kotlin.test.assertEquals

class RtpTimestampUnwrapperTest {

    @Test
    fun `monotonic timestamps unwrap to themselves`() {
        val u = RtpTimestampUnwrapper()
        assertEquals(0L, u.unwrap(0L))
        assertEquals(1_000L, u.unwrap(1_000L))
        assertEquals(2_000_000L, u.unwrap(2_000_000L))
    }

    @Test
    fun `wrap from near max to near zero is detected`() {
        val u = RtpTimestampUnwrapper()
        u.unwrap(0xFFFFFF00L)
        // Next packet wraps: low 32 bits = 100
        val unwrapped = u.unwrap(100L)
        assertEquals(0x100000000L + 100L, unwrapped)
    }

    @Test
    fun `out of order packet across the wrap boundary still unwraps correctly`() {
        val u = RtpTimestampUnwrapper()
        u.unwrap(0xFFFFFF00L)
        u.unwrap(100L) // wrap detected
        // An old packet just before the wrap arrives late
        val late = u.unwrap(0xFFFFFFFEL)
        // Should map to pre-wrap epoch (epoch=0)
        assertEquals(0xFFFFFFFEL, late)
    }
}
