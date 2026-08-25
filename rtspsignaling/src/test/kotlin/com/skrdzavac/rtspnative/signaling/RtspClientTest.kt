// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.signaling

import com.skrdzavac.rtspnative.core.Credentials
import com.skrdzavac.rtspnative.core.RtspError
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    /**
     * Field case: an NVR under a simultaneous DESCRIBE burst answered one
     * request with 500 while the rest succeeded. That is the server's own
     * transient failure, so it must surface as a retryable error rather
     * than a fatal protocol violation.
     */
    @Test
    fun `5xx DESCRIBE is a transient server error`() = runBlocking {
        val channel = ScriptedChannel(
            responses = listOf(
                response(200, "OK"),
                response(500, "Internal Server Error"),
            )
        )
        val client = RtspClient(channel = channel, baseUrl = "rtsp://cam.example/stream", credentials = null)

        val e = assertFailsWith<RtspError.ServerError> { client.handshake(videoOnly = true) }
        assertEquals(500, e.statusCode)
        assertEquals("Internal Server Error", e.reason)
        assertTrue(e.isRetryable)
        assertEquals("DESCRIBE", channel.sent.last().method)
    }

    @Test
    fun `501 and 505 stay fatal protocol errors`() = runBlocking {
        for ((code, reason) in listOf(501 to "Not Implemented", 505 to "RTSP Version Not Supported")) {
            val channel = ScriptedChannel(responses = listOf(response(200, "OK"), response(code, reason)))
            val client = RtspClient(channel = channel, baseUrl = "rtsp://cam.example/stream", credentials = null)

            val e = assertFailsWith<RtspError.Protocol> { client.handshake(videoOnly = true) }
            assertEquals(code, e.statusCode)
            assertFalse(e.isRetryable)
        }
    }

    @Test
    fun `4xx is a retryable protocol error carrying the status`() = runBlocking {
        val channel = ScriptedChannel(responses = listOf(response(200, "OK"), response(404, "Not Found")))
        val client = RtspClient(channel = channel, baseUrl = "rtsp://cam.example/stream", credentials = null)

        val e = assertFailsWith<RtspError.Protocol> { client.handshake(videoOnly = true) }
        assertEquals(404, e.statusCode)
        assertTrue(e.isRetryable)
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
