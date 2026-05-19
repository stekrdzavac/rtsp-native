// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.h265

import com.skrdzavac.rtspnative.core.RtpPacket
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class H265DepacketizerTest {

    @Test
    fun `emits single IDR NAL with start code on marker bit`() {
        // NAL header for IDR_W_RADL (type=19): byte0 = (19 << 1) = 0x26, byte1 = 0x01 (TID=1)
        val nal = byteArrayOf(0x26.toByte(), 0x01.toByte(), 0xAA.toByte(), 0xBB.toByte())
        val packet = packetWith(nal, marker = true, timestamp = 100)
        val dpk = H265Depacketizer()
        val aus = dpk.depacketize(packet)
        assertEquals(1, aus.size)
        assertEquals(100L, aus[0].ptsRtp)
        assertTrue(aus[0].isKeyframe)
        assertContentEquals(byteArrayOf(0, 0, 0, 1) + nal, aus[0].payload)
    }

    @Test
    fun `AP explodes into multiple NALs in one AU`() {
        // VPS (type 32): byte0=(32<<1)=0x40
        // SPS (type 33): byte0=(33<<1)=0x42
        // PPS (type 34): byte0=(34<<1)=0x44
        val vps = byteArrayOf(0x40.toByte(), 0x01.toByte(), 0xAA.toByte())
        val sps = byteArrayOf(0x42.toByte(), 0x01.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val pps = byteArrayOf(0x44.toByte(), 0x01.toByte(), 0xDD.toByte())

        // AP header: type=48 → byte0=(48<<1)=0x60, byte1=0x01
        val payload = byteArrayOf(0x60.toByte(), 0x01.toByte()) +
            byteArrayOf((vps.size ushr 8).toByte(), vps.size.toByte()) + vps +
            byteArrayOf((sps.size ushr 8).toByte(), sps.size.toByte()) + sps +
            byteArrayOf((pps.size ushr 8).toByte(), pps.size.toByte()) + pps

        val packet = packetWith(payload, marker = true, timestamp = 200)
        val dpk = H265Depacketizer()
        val aus = dpk.depacketize(packet)
        assertEquals(1, aus.size)
        val expected = byteArrayOf(0, 0, 0, 1) + vps +
            byteArrayOf(0, 0, 0, 1) + sps +
            byteArrayOf(0, 0, 0, 1) + pps
        assertContentEquals(expected, aus[0].payload)
        assertNotNull(dpk.parameterSets)
        assertContentEquals(vps, dpk.parameterSets?.vps)
        assertContentEquals(sps, dpk.parameterSets?.sps)
        assertContentEquals(pps, dpk.parameterSets?.pps)
    }

    @Test
    fun `FU reassembles an IDR NAL across multiple packets`() {
        // Target NAL: type=19 (IDR_W_RADL), payload AA BB CC DD
        // PayloadHdr: type=49 (FU), so byte0=(49<<1)=0x62, byte1=0x01
        val payloadHdr0 = 0x62.toByte()
        val payloadHdr1 = 0x01.toByte()
        // FU header: S=1, type=19 → 0x80 | 19 = 0x93
        val startHeader = 0x93.toByte()
        // mid: S=0, E=0, type=19 → 0x13
        val midHeader = 0x13.toByte()
        // end: S=0, E=1, type=19 → 0x53
        val endHeader = 0x53.toByte()

        val p1 = packetWith(byteArrayOf(payloadHdr0, payloadHdr1, startHeader, 0xAA.toByte()), timestamp = 300)
        val p2 = packetWith(byteArrayOf(payloadHdr0, payloadHdr1, midHeader, 0xBB.toByte()), timestamp = 300)
        val p3 = packetWith(byteArrayOf(payloadHdr0, payloadHdr1, endHeader, 0xCC.toByte(), 0xDD.toByte()), marker = true, timestamp = 300)

        val dpk = H265Depacketizer()
        assertTrue(dpk.depacketize(p1).isEmpty())
        assertTrue(dpk.depacketize(p2).isEmpty())
        val aus = dpk.depacketize(p3)
        assertEquals(1, aus.size)
        // Reconstructed NAL header: byte0 = (PayloadHdr.byte0 & 0x81) | (fuType << 1)
        //   PayloadHdr.byte0 = 0x62 = 0110_0010
        //   PayloadHdr.byte0 & 0x81 = 0x00
        //   fuType=19 → (19 << 1) = 0x26
        //   rebuiltByte0 = 0x26
        val expectedNalHeader = byteArrayOf(0x26.toByte(), 0x01.toByte())
        val expected = byteArrayOf(0, 0, 0, 1) +
            expectedNalHeader +
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        assertContentEquals(expected, aus[0].payload)
        assertTrue(aus[0].isKeyframe)
    }

    @Test
    fun `seedParameterSets populates store for SDP-derived sprop`() {
        val dpk = H265Depacketizer()
        val vps = byteArrayOf(1, 2, 3)
        val sps = byteArrayOf(4, 5)
        val pps = byteArrayOf(6, 7)
        dpk.seedParameterSets(vps, sps, pps)
        assertContentEquals(vps, dpk.parameterSets?.vps)
        assertContentEquals(sps, dpk.parameterSets?.sps)
        assertContentEquals(pps, dpk.parameterSets?.pps)
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
