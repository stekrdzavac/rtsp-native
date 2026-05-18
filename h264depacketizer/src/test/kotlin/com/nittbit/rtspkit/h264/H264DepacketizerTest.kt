package com.nittbit.rtspkit.h264

import com.nittbit.rtspkit.core.RtpPacket
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class H264DepacketizerTest {

    @Test
    fun `emits single NAL with start code on marker bit`() {
        // NAL type 5 (IDR) with body 0xAA 0xBB
        val nal = byteArrayOf(0x65.toByte(), 0xAA.toByte(), 0xBB.toByte())
        val packet = packetWith(nal, marker = true, timestamp = 100)
        val dpk = H264Depacketizer()
        val aus = dpk.depacketize(packet)
        assertEquals(1, aus.size)
        assertEquals(100L, aus[0].ptsRtp)
        assertTrue(aus[0].isKeyframe)
        assertContentEquals(byteArrayOf(0, 0, 0, 1) + nal, aus[0].payload)
    }

    @Test
    fun `STAP-A explodes into multiple NALs in one AU`() {
        // STAP-A header (type 24, NRI=3) followed by two NALs (SPS + PPS)
        val sps = byteArrayOf(0x67.toByte(), 0x42.toByte(), 0xC0.toByte(), 0x1E.toByte())
        val pps = byteArrayOf(0x68.toByte(), 0xCE.toByte(), 0x3C.toByte(), 0x80.toByte())
        val payload = byteArrayOf(0x78.toByte()) +
            byteArrayOf((sps.size ushr 8).toByte(), sps.size.toByte()) + sps +
            byteArrayOf((pps.size ushr 8).toByte(), pps.size.toByte()) + pps
        val packet = packetWith(payload, marker = true, timestamp = 200)
        val dpk = H264Depacketizer()
        val aus = dpk.depacketize(packet)
        assertEquals(1, aus.size)
        val expected = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps
        assertContentEquals(expected, aus[0].payload)
        // Parameter sets exposed
        assertContentEquals(sps, dpk.parameterSets?.sps)
        assertContentEquals(pps, dpk.parameterSets?.pps)
    }

    @Test
    fun `FU-A reassembles a NAL across multiple packets`() {
        // Original IDR slice NAL: header 0x65, payload AA BB CC DD
        // FU indicator = 0x7C (type=28, NRI from 0x65=3), FU header start: 0x85 (S=1, type=5)
        val fuIndicator = 0x7C.toByte()
        val startHeader = 0x85.toByte()
        val midHeader = 0x05.toByte()
        val endHeader = 0x45.toByte()

        val dpk = H264Depacketizer()

        val p1 = packetWith(byteArrayOf(fuIndicator, startHeader, 0xAA.toByte()), timestamp = 300)
        val p2 = packetWith(byteArrayOf(fuIndicator, midHeader, 0xBB.toByte()), timestamp = 300)
        val p3 = packetWith(byteArrayOf(fuIndicator, endHeader, 0xCC.toByte(), 0xDD.toByte()), marker = true, timestamp = 300)

        assertTrue(dpk.depacketize(p1).isEmpty())
        assertTrue(dpk.depacketize(p2).isEmpty())
        val aus = dpk.depacketize(p3)
        assertEquals(1, aus.size)
        val expected = byteArrayOf(0, 0, 0, 1, 0x65.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        assertContentEquals(expected, aus[0].payload)
        assertTrue(aus[0].isKeyframe)
    }

    @Test
    fun `timestamp change flushes previous AU`() {
        val dpk = H264Depacketizer()
        // First packet: non-IDR slice with no marker → buffered.
        dpk.depacketize(packetWith(byteArrayOf(0x61.toByte(), 0xAA.toByte()), timestamp = 100))
        // Second packet has new timestamp + marker — flushes the previous AU,
        // then emits its own AU on the marker.
        val aus = dpk.depacketize(packetWith(byteArrayOf(0x61.toByte(), 0xBB.toByte()), marker = true, timestamp = 200))
        assertEquals(2, aus.size)
        assertEquals(100L, aus[0].ptsRtp)
        assertEquals(200L, aus[1].ptsRtp)
    }

    @Test
    fun `seedParameterSets populates store for SDP-derived sprop`() {
        val dpk = H264Depacketizer()
        val sps = byteArrayOf(1, 2, 3)
        val pps = byteArrayOf(4, 5)
        dpk.seedParameterSets(sps, pps)
        assertNotNull(dpk.parameterSets)
        assertContentEquals(sps, dpk.parameterSets?.sps)
        assertContentEquals(pps, dpk.parameterSets?.pps)
    }

    /**
     * Builds a synthetic RTP packet wrapping [payload].
     */
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
