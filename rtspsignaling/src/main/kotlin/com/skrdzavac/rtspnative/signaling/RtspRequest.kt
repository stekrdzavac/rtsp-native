package com.skrdzavac.rtspnative.signaling

/**
 * RTSP/1.0 request builder. Produces byte-exact wire format.
 */
data class RtspRequest(
    val method: String,
    val uri: String,
    val cseq: Int,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray? = null,
) {
    fun toBytes(): ByteArray {
        val sb = StringBuilder()
        sb.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n")
        sb.append("CSeq: ").append(cseq).append("\r\n")
        for ((k, v) in headers) {
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        body?.let { sb.append("Content-Length: ").append(it.size).append("\r\n") }
        sb.append("\r\n")
        val headerBytes = sb.toString().toByteArray(Charsets.ISO_8859_1)
        return if (body != null) headerBytes + body else headerBytes
    }

    companion object {
        const val USER_AGENT = "RTSPKit-Android/1.0"

        fun options(uri: String, cseq: Int, sessionId: String? = null, auth: String? = null): RtspRequest =
            base("OPTIONS", uri, cseq, sessionId, auth)

        fun describe(uri: String, cseq: Int, auth: String? = null): RtspRequest {
            val h = mutableListOf<Pair<String, String>>()
            h += "User-Agent" to USER_AGENT
            h += "Accept" to "application/sdp"
            auth?.let { h += "Authorization" to it }
            return RtspRequest("DESCRIBE", uri, cseq, h)
        }

        fun setup(
            uri: String,
            cseq: Int,
            transportHeader: String,
            sessionId: String? = null,
            auth: String? = null,
        ): RtspRequest {
            val h = mutableListOf<Pair<String, String>>()
            h += "User-Agent" to USER_AGENT
            h += "Transport" to transportHeader
            sessionId?.let { h += "Session" to it }
            auth?.let { h += "Authorization" to it }
            return RtspRequest("SETUP", uri, cseq, h)
        }

        fun play(uri: String, cseq: Int, sessionId: String, auth: String? = null): RtspRequest {
            val h = mutableListOf<Pair<String, String>>()
            h += "User-Agent" to USER_AGENT
            h += "Session" to sessionId
            h += "Range" to "npt=0.000-"
            auth?.let { h += "Authorization" to it }
            return RtspRequest("PLAY", uri, cseq, h)
        }

        fun teardown(uri: String, cseq: Int, sessionId: String, auth: String? = null): RtspRequest =
            base("TEARDOWN", uri, cseq, sessionId, auth)

        fun getParameter(uri: String, cseq: Int, sessionId: String, auth: String? = null): RtspRequest =
            base("GET_PARAMETER", uri, cseq, sessionId, auth)

        private fun base(method: String, uri: String, cseq: Int, sessionId: String?, auth: String?): RtspRequest {
            val h = mutableListOf<Pair<String, String>>()
            h += "User-Agent" to USER_AGENT
            sessionId?.let { h += "Session" to it }
            auth?.let { h += "Authorization" to it }
            return RtspRequest(method, uri, cseq, h)
        }
    }
}
