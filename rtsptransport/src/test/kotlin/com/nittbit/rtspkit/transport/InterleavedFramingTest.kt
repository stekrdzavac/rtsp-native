package com.nittbit.rtspkit.transport

import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InterleavedFramingTest {

    @Test
    fun `parses a single interleaved frame`() {
        // $ + channel=0 + length=4 + payload AA BB CC DD
        val bytes = byteArrayOf(
            0x24.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x04.toByte(),
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(),
        )
        val event = InterleavedFraming.readOne(ByteArrayInputStream(bytes))
        assertTrue(event is InterleavedFraming.Event.Frame)
        assertEquals(0, event.channel)
        assertContentEquals(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
            event.payload,
        )
    }

    @Test
    fun `parses an RTSP response message with body`() {
        val response = (
            "RTSP/1.0 200 OK\r\n" +
                "CSeq: 1\r\n" +
                "Content-Length: 3\r\n" +
                "\r\n" +
                "abc"
            ).toByteArray(Charsets.ISO_8859_1)
        val event = InterleavedFraming.readOne(ByteArrayInputStream(response))
        assertTrue(event is InterleavedFraming.Event.Message)
        assertEquals(200, event.response.statusCode)
        assertContentEquals(byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()), event.response.body)
    }

    @Test
    fun `handles two back-to-back events`() {
        val msg = "RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        val frame = byteArrayOf(0x24.toByte(), 0x01.toByte(), 0x00.toByte(), 0x02.toByte(), 0x11.toByte(), 0x22.toByte())
        val input = ByteArrayInputStream(msg + frame)

        val first = InterleavedFraming.readOne(input) as InterleavedFraming.Event.Message
        assertEquals(200, first.response.statusCode)

        val second = InterleavedFraming.readOne(input) as InterleavedFraming.Event.Frame
        assertEquals(1, second.channel)
        assertEquals(2, second.payload.size)
    }

    @Test
    fun `returns null on clean EOF`() {
        val input = ByteArrayInputStream(ByteArray(0))
        assertNull(InterleavedFraming.readOne(input))
    }
}
