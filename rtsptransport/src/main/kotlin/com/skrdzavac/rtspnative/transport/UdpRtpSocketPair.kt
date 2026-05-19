// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.transport

import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * A pair of UDP sockets for one media track. RFC 3550 §11 convention:
 * RTP on the even port, RTCP on the next odd port.
 *
 * We allocate by binding to ephemeral ports and retrying until the
 * randomly-chosen RTP port is even. Camera SETUP responses will quote
 * these ports back as the `client_port` pair.
 */
class UdpRtpSocketPair private constructor(
    val rtpSocket: DatagramSocket,
    val rtcpSocket: DatagramSocket,
) {
    val rtpPort: Int get() = rtpSocket.localPort
    val rtcpPort: Int get() = rtcpSocket.localPort

    fun close() {
        runCatching { rtpSocket.close() }
        runCatching { rtcpSocket.close() }
    }

    companion object {
        private const val MAX_ATTEMPTS = 50

        fun bind(): UdpRtpSocketPair {
            repeat(MAX_ATTEMPTS) {
                val rtp = DatagramSocket(InetSocketAddress(0))
                if (rtp.localPort and 1 == 1) {
                    // Got odd port; try again.
                    rtp.close()
                    return@repeat
                }
                val rtcp = runCatching { DatagramSocket(InetSocketAddress(rtp.localPort + 1)) }.getOrNull()
                if (rtcp == null) {
                    // The neighbouring odd port was taken; release this pair and try again.
                    rtp.close()
                    return@repeat
                }
                // Bigger buffer reduces UDP drops under transient load.
                rtp.receiveBufferSize = 1024 * 1024
                rtcp.receiveBufferSize = 256 * 1024
                return UdpRtpSocketPair(rtp, rtcp)
            }
            throw IllegalStateException("could not bind UDP socket pair (even/odd) after $MAX_ATTEMPTS attempts")
        }
    }
}
