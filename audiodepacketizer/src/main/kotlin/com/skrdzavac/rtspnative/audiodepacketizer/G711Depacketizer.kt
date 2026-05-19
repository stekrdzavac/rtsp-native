// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.audiodepacketizer

import com.skrdzavac.rtspnative.core.AccessUnit
import com.skrdzavac.rtspnative.core.RtpPacket

/**
 * G.711 RFC 3551 — the RTP payload IS the audio frame. One byte per
 * sample, 8 kHz. Used for both μ-law (payload type 0) and A-law (payload
 * type 8); the depacketizer doesn't care about the law variant.
 */
class G711Depacketizer : AudioDepacketizer {
    override fun depacketize(packet: RtpPacket): List<AccessUnit.Audio> {
        if (packet.payloadLength == 0) return emptyList()
        return listOf(
            AccessUnit.Audio(
                ptsRtp = packet.timestamp,
                payload = packet.copyPayload(),
            )
        )
    }
}
