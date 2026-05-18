package com.nittbit.rtspkit.signaling

/**
 * Parsed RTSP/1.0 response. Header keys are normalized to lowercase for
 * case-insensitive lookup; [rawHeaders] preserves wire-format casing.
 */
data class RtspResponse(
    val statusCode: Int,
    val statusMessage: String,
    val rawHeaders: List<Pair<String, String>>,
    val body: ByteArray,
) {
    private val lowerHeaders: Map<String, String> =
        rawHeaders.associate { (k, v) -> k.lowercase() to v }

    fun header(name: String): String? = lowerHeaders[name.lowercase()]

    val cseq: Int? get() = header("CSeq")?.toIntOrNull()
    val contentLength: Int get() = header("Content-Length")?.toIntOrNull() ?: 0
    val sessionId: String? get() = header("Session")?.substringBefore(';')?.trim()

    companion object {
        /**
         * Find the index *after* the CRLFCRLF that separates headers from body,
         * or -1 if not present yet.
         */
        fun headerEndIndex(data: ByteArray, offset: Int, length: Int): Int {
            val end = offset + length
            var i = offset
            while (i + 3 < end) {
                if (data[i] == 0x0D.toByte() && data[i + 1] == 0x0A.toByte() &&
                    data[i + 2] == 0x0D.toByte() && data[i + 3] == 0x0A.toByte()
                ) return i + 4
                i++
            }
            return -1
        }

        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): RtspResponse? {
            val headerEnd = headerEndIndex(data, offset, length)
            if (headerEnd < 0) return null
            val headerText = String(data, offset, headerEnd - offset, Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
            if (lines.isEmpty()) return null

            val statusLine = lines[0]
            if (!statusLine.startsWith("RTSP/")) return null
            val parts = statusLine.split(' ', limit = 3)
            if (parts.size < 2) return null
            val statusCode = parts[1].toIntOrNull() ?: return null
            val statusMessage = if (parts.size == 3) parts[2] else ""

            val headers = lines.drop(1).mapNotNull { line ->
                val sep = line.indexOf(':')
                if (sep <= 0) return@mapNotNull null
                val k = line.substring(0, sep).trim()
                val v = line.substring(sep + 1).trim()
                k to v
            }

            val contentLength = headers
                .firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }
                ?.second?.toIntOrNull() ?: 0

            val bodyEnd = headerEnd + contentLength
            if (bodyEnd > offset + length) return null
            val body = if (contentLength > 0) {
                data.copyOfRange(headerEnd, bodyEnd)
            } else {
                ByteArray(0)
            }

            return RtspResponse(statusCode, statusMessage, headers, body)
        }
    }
}
