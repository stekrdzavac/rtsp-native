package com.nittbit.rtspkit.transport

import com.nittbit.rtspkit.signaling.RtspMessageChannel
import kotlinx.coroutines.flow.SharedFlow

/**
 * Common shape for an RTSP transport. Implementations:
 *   - [TcpInterleavedTransport] — RTSP + RTP/RTCP on a single TCP socket
 *     using RFC 2326 §10.12 `$`-framing.
 *   - [UdpTransport] — RTSP on a TCP socket, RTP/RTCP on separate UDP
 *     socket pairs per track.
 *
 * Both expose:
 *   - The [RtspMessageChannel] for RTSP control (send / receive RtspRequest/Response).
 *   - A unified [frames] flow keyed by synthetic interleaved channel ids
 *     (0 = video RTP, 1 = video RTCP, 2 = audio RTP, 3 = audio RTCP) so
 *     [RtspSession] consumes the same shape regardless of the underlying
 *     transport.
 */
interface RtspTransport : RtspMessageChannel {
    val frames: SharedFlow<InterleavedFrame>
}
