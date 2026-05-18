package com.skrdzavac.rtspnative.transport

/**
 * One RTP or RTCP packet carried over the RTSP TCP connection per
 * RFC 2326 §10.12. [channel] is the interleaved channel id assigned at
 * SETUP time.
 */
data class InterleavedFrame(
    val channel: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is InterleavedFrame && channel == other.channel && payload.contentEquals(other.payload)

    override fun hashCode(): Int = channel * 31 + payload.contentHashCode()
}
