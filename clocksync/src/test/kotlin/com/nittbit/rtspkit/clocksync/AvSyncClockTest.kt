package com.nittbit.rtspkit.clocksync

import com.nittbit.rtspkit.core.RtcpPacket
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvSyncClockTest {

    @Test
    fun `returns null until SR and presentation anchor land`() {
        val clock = AvSyncClock()
        assertFalse(clock.isReady())
        assertNull(clock.systemTimeForVideoRtp(1000))
    }

    @Test
    fun `video frame at audio epoch maps to the audio sysTime`() {
        val clock = AvSyncClock()
        // Both SRs claim the same NTP instant (camera-aligned) but
        // different RTP timestamps because their clocks are independent.
        val ntp = encodeNtp(secondsSince1900 = 1_000L, fractionalSecond = 0.0)
        clock.onVideoSenderReport(
            RtcpPacket.SenderReport(1L, ntp, 9_000_000L, 0L, 0L),
            clockRateHz = 90_000,
        )
        clock.onAudioSenderReport(
            RtcpPacket.SenderReport(2L, ntp, 16_000L, 0L, 0L),
            clockRateHz = 16_000,
        )
        // Audio sample with RTP TS 16_000 just played at sysNano 5_000_000.
        clock.anchorPresentation(audioRtpTs = 16_000L, sysTimeNs = 5_000_000L)
        assertTrue(clock.isReady())

        // A video frame at RTP TS 9_000_000 corresponds to the same wall
        // clock as the anchor's audio frame, so it should render at the
        // same system time.
        val sys = clock.systemTimeForVideoRtp(9_000_000L)
        assertNotNull(sys)
        assertEquals(5_000_000L, sys)
    }

    @Test
    fun `one second later in video RTP space maps to sysTime + 1 billion nanos`() {
        val clock = AvSyncClock()
        val ntp = encodeNtp(secondsSince1900 = 1_000L, fractionalSecond = 0.0)
        clock.onVideoSenderReport(
            RtcpPacket.SenderReport(1L, ntp, 0L, 0L, 0L),
            clockRateHz = 90_000,
        )
        clock.onAudioSenderReport(
            RtcpPacket.SenderReport(2L, ntp, 0L, 0L, 0L),
            clockRateHz = 16_000,
        )
        clock.anchorPresentation(audioRtpTs = 0L, sysTimeNs = 0L)

        val one_second_later = clock.systemTimeForVideoRtp(90_000L)!!
        // Allow for small rounding from NTP fraction math.
        assertTrue(abs(one_second_later - 1_000_000_000L) < 100, "got $one_second_later")
    }

    @Test
    fun `anchorPresentation is a no-op without an audio SR`() {
        val clock = AvSyncClock()
        clock.anchorPresentation(1000L, 5000L)
        assertFalse(clock.isReady())
    }

    /** RTCP-style 64-bit NTP value from human-readable components. */
    private fun encodeNtp(secondsSince1900: Long, fractionalSecond: Double): Long {
        val fracBits = (fractionalSecond * (1L shl 32)).toLong() and 0xFFFFFFFFL
        return (secondsSince1900 shl 32) or fracBits
    }
}
