package com.nittbit.rtspkit.audiodepacketizer

import com.nittbit.rtspkit.core.RtpPacket
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class G711DepacketizerTest {

    @Test
    fun `passes payload through as a single audio access unit`() {
        val payload = byteArrayOf(0xFF.toByte(), 0x7F.toByte(), 0x00.toByte(), 0x80.toByte())
        val depacketizer = G711Depacketizer()
        val units = depacketizer.depacketize(packetWith(payload, timestamp = 4242))
        assertEquals(1, units.size)
        assertContentEquals(payload, units[0].payload)
        assertEquals(4242L, units[0].ptsRtp)
    }

    @Test
    fun `drops empty payload`() {
        val depacketizer = G711Depacketizer()
        val units = depacketizer.depacketize(packetWith(ByteArray(0)))
        assertTrue(units.isEmpty())
    }

    private fun packetWith(payload: ByteArray, timestamp: Long = 0): RtpPacket {
        val ts = ByteArray(4)
        ts[0] = (timestamp ushr 24).toByte()
        ts[1] = (timestamp ushr 16).toByte()
        ts[2] = (timestamp ushr 8).toByte()
        ts[3] = (timestamp and 0xFF).toByte()
        val header = byteArrayOf(
            0x80.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x01.toByte(),
            ts[0], ts[1], ts[2], ts[3],
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
        )
        return RtpPacket.parse(header + payload)!!
    }
}
