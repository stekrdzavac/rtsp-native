package com.nittbit.rtspkit.audiodepacketizer

import com.nittbit.rtspkit.core.AccessUnit
import com.nittbit.rtspkit.core.RtpPacket

/**
 * Reassembles an RTP audio payload into one or more decoder-ready
 * [AccessUnit.Audio]s. Implementations are stateless for G.711/L16 and
 * mildly stateful for AAC (interleaved AU-headers).
 */
interface AudioDepacketizer {
    fun depacketize(packet: RtpPacket): List<AccessUnit.Audio>
}
