package com.nittbit.rtspkit.core

sealed class TransportPreference {
    /** Try TCP-interleaved first, fall back to UDP. (Stage 2 — currently behaves like TcpInterleaved.) */
    object Auto : TransportPreference()

    /** RTP/RTCP multiplexed over the RTSP TCP connection (RFC 2326 §10.12). */
    object TcpInterleaved : TransportPreference()

    /** UDP unicast — Stage 2 only. */
    object Udp : TransportPreference()
}
