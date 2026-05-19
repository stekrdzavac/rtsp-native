// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.audiodepacketizer

import com.skrdzavac.rtspnative.core.RtpPacket
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AacDepacketizerTest {

    @Test
    fun `extracts a single AAC AU from one RTP packet`() {
        // AU-headers-length = 16 bits (one 16-bit header)
        // AU header (16 bits): size=4 (13 bits = 0x0004), index=0 (3 bits)
        //   bits: 0000000000001000_000 → first 16 bits + 3 padding bits → 0x00 0x20 0x00
        //   actually 13 bits 0000000000001 then 3 bits 000 → packed: 00000000 00010000 (0x00, 0x20)
        val auHeaders = byteArrayOf(0x00.toByte(), 0x20.toByte())  // size=4, index=0
        val auHeadersLengthHi = 0x00.toByte()
        val auHeadersLengthLo = 0x10.toByte()  // 16 bits
        val auData = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val rtpPayload = byteArrayOf(auHeadersLengthHi, auHeadersLengthLo) + auHeaders + auData

        val packet = packetWith(rtpPayload, timestamp = 1000)
        val depacketizer = AacDepacketizer()
        val units = depacketizer.depacketize(packet)
        assertEquals(1, units.size)
        assertContentEquals(auData, units[0].payload)
        assertEquals(1000L, units[0].ptsRtp)
    }

    @Test
    fun `extracts two AAC AUs from one packet`() {
        // 2 AUs each 16-bit header: total 32 bits of headers (4 bytes)
        // AU 0: size=3, index=0    → 0000000000011 000 = 0x00 0x18
        // AU 1: size=2, index-delta=0 → 0000000000010 000 = 0x00 0x10
        val auHeaders = byteArrayOf(0x00.toByte(), 0x18.toByte(), 0x00.toByte(), 0x10.toByte())
        val auHeadersLength = byteArrayOf(0x00.toByte(), 0x20.toByte())  // 32 bits
        val au0 = byteArrayOf(0x11.toByte(), 0x22.toByte(), 0x33.toByte())
        val au1 = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val rtpPayload = auHeadersLength + auHeaders + au0 + au1

        val depacketizer = AacDepacketizer()
        val units = depacketizer.depacketize(packetWith(rtpPayload, timestamp = 2000))
        assertEquals(2, units.size)
        assertContentEquals(au0, units[0].payload)
        assertContentEquals(au1, units[1].payload)
    }

    private fun packetWith(payload: ByteArray, marker: Boolean = false, timestamp: Long = 0): RtpPacket {
        val markerByte = if (marker) 0xE0.toByte() else 0x60.toByte()
        val ts = ByteArray(4)
        ts[0] = (timestamp ushr 24).toByte()
        ts[1] = (timestamp ushr 16).toByte()
        ts[2] = (timestamp ushr 8).toByte()
        ts[3] = (timestamp and 0xFF).toByte()
        val header = byteArrayOf(
            0x80.toByte(), markerByte,
            0x00.toByte(), 0x01.toByte(),
            ts[0], ts[1], ts[2], ts[3],
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
        )
        return RtpPacket.parse(header + payload)!!
    }
}
