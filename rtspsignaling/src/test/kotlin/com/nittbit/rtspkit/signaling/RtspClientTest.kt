package com.nittbit.rtspkit.signaling

import com.nittbit.rtspkit.core.Credentials
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RtspClientTest {

    /**
     * Drives the client through OPTIONS → DESCRIBE (401, then 200) →
     * SETUP → PLAY against a scripted fake channel. Asserts the request
     * sequencing and that the second DESCRIBE carries an Authorization
     * header.
     */
    @Test
    fun `re-authenticates on 401 and completes handshake`() = runBlocking {
        val sdp = """
            v=0
            o=- 1 1 IN IP4 192.168.1.10
            s=Test
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=control:trackID=1
        """.trimIndent()

        val channel = ScriptedChannel(
            responses = listOf(
                // OPTIONS 200
                response(200, "OK"),
                // DESCRIBE 401 with Digest challenge
                response(
                    401, "Unauthorized",
                    headers = listOf(
                        "WWW-Authenticate" to """Digest realm="IPCAM", nonce="abc", qop="auth""""
                    ),
                ),
                // DESCRIBE retry — 200 with SDP body
                response(200, "OK", body = sdp.toByteArray(Charsets.UTF_8)),
                // SETUP 200 with Transport+Session
                response(
                    200, "OK",
                    headers = listOf(
                        "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1",
                        "Session" to "12345678;timeout=60",
                    ),
                ),
                // PLAY 200
                response(200, "OK", headers = listOf("Session" to "12345678")),
            )
        )

        val client = RtspClient(
            channel = channel,
            baseUrl = "rtsp://cam.example/stream",
            credentials = Credentials("admin", "1234"),
        )

        val result = client.handshake(videoOnly = true)
        assertEquals(0, result.videoTrack.rtpChannel)
        assertEquals(1, result.videoTrack.rtcpChannel)
        assertEquals("12345678", result.videoTrack.sessionId)

        // First request: OPTIONS
        assertEquals("OPTIONS", channel.sent[0].method)
        // Second request: DESCRIBE (no auth yet)
        assertEquals("DESCRIBE", channel.sent[1].method)
        assertTrue(channel.sent[1].headers.none { it.first == "Authorization" })
        // Third request: DESCRIBE again (now with Authorization)
        assertEquals("DESCRIBE", channel.sent[2].method)
        assertTrue(channel.sent[2].headers.any { it.first == "Authorization" && it.second.startsWith("Digest") })
        // Fourth: SETUP
        assertEquals("SETUP", channel.sent[3].method)
        // Fifth: PLAY
        assertEquals("PLAY", channel.sent[4].method)
    }

    private fun response(
        code: Int,
        message: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray = ByteArray(0),
    ): RtspResponse = RtspResponse(code, message, headers, body)

    private class ScriptedChannel(val responses: List<RtspResponse>) : RtspMessageChannel {
        val sent = mutableListOf<RtspRequest>()
        private var index = 0

        override suspend fun send(request: RtspRequest) {
            sent += request
        }

        override suspend fun receive(): RtspResponse {
            val r = responses[index++]
            return r
        }

        override fun close() {}
    }
}
