package com.nittbit.rtspkit.core

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RtpPacketTest {

    @Test
    fun `parses minimum 12-byte header with payload`() {
        // V=2, P=0, X=0, CC=0, M=0, PT=96, seq=0x1234, ts=0x1234, ssrc=0xDEADBEEF, payload AA BB CC DD
        val bytes = byteArrayOf(
            0x80.toByte(), 0x60.toByte(),
            0x12.toByte(), 0x34.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x12.toByte(), 0x34.toByte(),
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(),
        )

        val packet = RtpPacket.parse(bytes)
        assertNotNull(packet)
        assertEquals(96, packet.payloadType)
        assertFalse(packet.marker)
        assertEquals(0x1234, packet.sequenceNumber)
        assertEquals(0x1234L, packet.timestamp)
        assertEquals(0xDEADBEEFL, packet.ssrc)
        assertEquals(0, packet.csrcs.size)
        assertEquals(12, packet.payloadOffset)
        assertEquals(4, packet.payloadLength)
        assertContentEquals(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
            packet.copyPayload(),
        )
    }

    @Test
    fun `parses marker bit`() {
        // M=1, PT=96
        val bytes = byteArrayOf(
            0x80.toByte(), 0xE0.toByte(),
            0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
        )
        val packet = RtpPacket.parse(bytes)
        assertNotNull(packet)
        assertTrue(packet.marker)
        assertEquals(96, packet.payloadType)
    }

    @Test
    fun `parses CSRC list`() {
        // CC=2, two CSRC entries
        val bytes = byteArrayOf(
            0x82.toByte(), 0x60.toByte(),
            0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x44.toByte(),
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(),
            0xFF.toByte(), // payload
        )
        val packet = RtpPacket.parse(bytes)
        assertNotNull(packet)
        assertEquals(2, packet.csrcs.size)
        assertEquals(0x11223344L, packet.csrcs[0])
        assertEquals(0xAABBCCDDL, packet.csrcs[1])
        assertEquals(20, packet.payloadOffset)
        assertEquals(1, packet.payloadLength)
    }

    @Test
    fun `parses extension header and skips it`() {
        // X=1, no CSRC. Extension: profile=0xBEDE, length=1 word (4 bytes ext payload), then RTP payload.
        val bytes = byteArrayOf(
            0x90.toByte(), 0x60.toByte(),
            0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            // extension header
            0xBE.toByte(), 0xDE.toByte(),
            0x00.toByte(), 0x01.toByte(),
            // extension payload (1 word = 4 bytes)
            0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte(),
            // RTP payload
            0xFF.toByte(), 0xEE.toByte(),
        )
        val packet = RtpPacket.parse(bytes)
        assertNotNull(packet)
        assertTrue(packet.hasExtension)
        assertEquals(0xBEDE, packet.extensionProfile)
        assertEquals(20, packet.payloadOffset)
        assertEquals(2, packet.payloadLength)
    }

    @Test
    fun `parses padding`() {
        // P=1. Last byte of full buffer indicates padding bytes (incl itself).
        val bytes = byteArrayOf(
            0xA0.toByte(), 0x60.toByte(),
            0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            // 2 real payload bytes + 2 padding bytes (last byte = 2)
            0xAA.toByte(), 0xBB.toByte(),
            0x00.toByte(), 0x02.toByte(),
        )
        val packet = RtpPacket.parse(bytes)
        assertNotNull(packet)
        assertEquals(12, packet.payloadOffset)
        assertEquals(2, packet.payloadLength)
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), packet.copyPayload())
    }

    @Test
    fun `rejects buffer shorter than header`() {
        val bytes = ByteArray(11)
        assertNull(RtpPacket.parse(bytes))
    }

    @Test
    fun `rejects wrong RTP version`() {
        // V=1
        val bytes = byteArrayOf(
            0x40.toByte(), 0x60.toByte(),
            0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
        )
        assertNull(RtpPacket.parse(bytes))
    }

    @Test
    fun `rejects buffer too short for declared CSRC list`() {
        // CC=2 but only 12 bytes total
        val bytes = byteArrayOf(
            0x82.toByte(), 0x60.toByte(),
            0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
        )
        assertNull(RtpPacket.parse(bytes))
    }

    @Test
    fun `rejects buffer too short for declared extension length`() {
        // X=1, extension length=4 words but no extension bytes
        val bytes = byteArrayOf(
            0x90.toByte(), 0x60.toByte(),
            0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0xBE.toByte(), 0xDE.toByte(),
            0x00.toByte(), 0x04.toByte(),
        )
        assertNull(RtpPacket.parse(bytes))
    }

    @Test
    fun `rejects padding that exceeds payload`() {
        val bytes = byteArrayOf(
            0xA0.toByte(), 0x60.toByte(),
            0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            // payload byte where the last byte claims 5 padding bytes (impossible)
            0x05.toByte(),
        )
        assertNull(RtpPacket.parse(bytes))
    }
}
