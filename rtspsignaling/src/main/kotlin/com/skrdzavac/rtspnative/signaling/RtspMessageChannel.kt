// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.signaling

/**
 * Bidirectional channel for RTSP request/response exchange. Implemented by
 * `:rtsptransport` (over TCP-interleaved or a raw socket).
 *
 * RTP/RTCP frames carried on the same TCP connection are surfaced by the
 * transport via separate Flows — they are not visible through this
 * interface.
 */
interface RtspMessageChannel {
    suspend fun send(request: RtspRequest)
    suspend fun receive(): RtspResponse
    fun close()
}
