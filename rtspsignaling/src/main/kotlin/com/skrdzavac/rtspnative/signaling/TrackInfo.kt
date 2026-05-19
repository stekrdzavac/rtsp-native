// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.signaling

import com.skrdzavac.rtspnative.core.AudioCodec
import com.skrdzavac.rtspnative.core.VideoCodec

/**
 * One media track described by SDP. The SETUP step refines this into a
 * [NegotiatedTrack] that carries the interleaved channel ids.
 */
sealed class TrackInfo {
    abstract val payloadType: Int
    abstract val clockRate: Int
    abstract val controlUrl: String

    data class Video(
        override val payloadType: Int,
        val codec: VideoCodec,
        override val clockRate: Int,
        override val controlUrl: String,
        val vps: ByteArray? = null,
        val sps: ByteArray? = null,
        val pps: ByteArray? = null,
        val packetizationMode: Int = 1,
    ) : TrackInfo()

    data class Audio(
        override val payloadType: Int,
        val codec: AudioCodec,
        override val clockRate: Int,
        override val controlUrl: String,
        val channels: Int = 1,
        val configBytes: ByteArray? = null,
        // RFC 3640 AAC fmtp parameters (defaults match AAC-hbr mode)
        val sizeLength: Int = 13,
        val indexLength: Int = 3,
        val indexDeltaLength: Int = 3,
    ) : TrackInfo()
}

/**
 * Output of SETUP: a track ready to play, with the assigned interleaved
 * channel ids and the session id from the server.
 */
data class NegotiatedTrack(
    val info: TrackInfo,
    val rtpChannel: Int,
    val rtcpChannel: Int,
    val sessionId: String,
)
