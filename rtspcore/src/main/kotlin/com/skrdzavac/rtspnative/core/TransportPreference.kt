package com.skrdzavac.rtspnative.core

/**
 * How RTP/RTCP gets carried.
 *
 * For real-world camera apps, the practical advice is:
 *   - **WAN / public internet**: use [Auto] (or [TcpInterleaved] explicitly).
 *     UDP requires inbound packets through the client's NAT, which most
 *     home/cellular routers drop.
 *   - **LAN**: either works. [Udp] is slightly lower latency; [TcpInterleaved]
 *     is more reliable through misbehaving switches and NAT-aware firewalls.
 */
sealed class TransportPreference {
    /**
     * Let the library pick. Currently equivalent to [TcpInterleaved] — the
     * safe default that works behind NATs. Reserved for a future Stage 3
     * fallback (try UDP first for lower latency, fall back to TCP on
     * first-frame timeout).
     */
    object Auto : TransportPreference()

    /** RTP/RTCP multiplexed over the RTSP TCP connection (RFC 2326 §10.12). */
    object TcpInterleaved : TransportPreference()

    /** RTP/RTCP over separate UDP socket pairs. LAN-friendly; usually fails through NAT. */
    object Udp : TransportPreference()
}
