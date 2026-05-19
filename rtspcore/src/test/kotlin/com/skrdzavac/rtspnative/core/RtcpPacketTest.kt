// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RtcpPacketTest {

    @Test
    fun `parses a minimal Sender Report with no report blocks`() {
        // V=2, P=0, RC=0, PT=200 (SR), length=6 (in 32-bit words minus 1)
        // sender info: SSRC, NTPhi, NTPlo, RTP ts, packet count, octet count
        val bytes = byteArrayOf(
            0x80.toByte(), 0xC8.toByte(),
            0x00.toByte(), 0x06.toByte(),
            // SSRC = 0x11223344
            0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x44.toByte(),
            // NTP hi = 0xE0000000
            0xE0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            // NTP lo = 0x80000000
            0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            // RTP timestamp = 0x00010000
            0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(),
            // packet count = 0x000003E8 (1000)
            0x00.toByte(), 0x00.toByte(), 0x03.toByte(), 0xE8.toByte(),
            // octet count = 0x000F4240 (1000000)
            0x00.toByte(), 0x0F.toByte(), 0x42.toByte(), 0x40.toByte(),
        )

        val packets = RtcpPacket.parseAll(bytes)
        assertEquals(1, packets.size)
        val sr = packets[0] as RtcpPacket.SenderReport
        assertEquals(0x11223344L, sr.senderSsrc)
        assertEquals(0xE000000080000000UL.toLong(), sr.ntpTimestamp)
        assertEquals(0x00010000L, sr.rtpTimestamp)
        assertEquals(1000L, sr.senderPacketCount)
        assertEquals(1_000_000L, sr.senderOctetCount)
    }

    @Test
    fun `parses compound RTCP packet keeping SR and ignoring unknown types`() {
        // SR (PT=200, length=6) then unknown packet (PT=201, length=1, 4 extra bytes)
        val sr = byteArrayOf(
            0x80.toByte(), 0xC8.toByte(),
            0x00.toByte(), 0x06.toByte(),
            0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x44.toByte(),
            0xE0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x03.toByte(), 0xE8.toByte(),
            0x00.toByte(), 0x0F.toByte(), 0x42.toByte(), 0x40.toByte(),
        )
        // RR (PT=201) length=1 (2 words total = 8 bytes), no report blocks
        val rr = byteArrayOf(
            0x80.toByte(), 0xC9.toByte(),
            0x00.toByte(), 0x01.toByte(),
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(),
        )
        val packets = RtcpPacket.parseAll(sr + rr)
        // Only SR is recognized in Stage 1; RR is silently skipped.
        assertTrue(packets.any { it is RtcpPacket.SenderReport })
    }

    @Test
    fun `parseAll returns empty for malformed buffer`() {
        val tooShort = ByteArray(2)
        assertTrue(RtcpPacket.parseAll(tooShort).isEmpty())

        // Length-overflow: claims 100 words
        val overflow = byteArrayOf(
            0x80.toByte(), 0xC8.toByte(),
            0x00.toByte(), 0x64.toByte(),
        )
        assertTrue(RtcpPacket.parseAll(overflow).isEmpty())
    }
}
