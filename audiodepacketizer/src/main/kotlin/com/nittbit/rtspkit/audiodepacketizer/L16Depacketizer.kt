package com.nittbit.rtspkit.audiodepacketizer

import com.nittbit.rtspkit.core.AccessUnit
import com.nittbit.rtspkit.core.RtpPacket

/**
 * L16 RFC 3551 — 16-bit big-endian PCM. Like G.711, one payload per RTP
 * packet, no fragmentation. Caller is responsible for the BE→LE byte swap
 * before feeding to AudioTrack (which expects native-endian PCM).
 */
class L16Depacketizer : AudioDepacketizer {
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
