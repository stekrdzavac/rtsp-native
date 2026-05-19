// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.core

/**
 * Minimal RTCP packet model. Stage 1 only recognizes the Sender Report (SR)
 * payload since that's what ClockSync needs to anchor wall-clock PTS. Other
 * RTCP packet types in a compound bundle are tolerated and skipped.
 */
sealed class RtcpPacket {

    data class SenderReport(
        val senderSsrc: Long,
        val ntpTimestamp: Long,
        val rtpTimestamp: Long,
        val senderPacketCount: Long,
        val senderOctetCount: Long,
    ) : RtcpPacket()

    companion object {
        private const val PT_SR = 200

        fun parseAll(
            data: ByteArray,
            offset: Int = 0,
            length: Int = data.size - offset,
        ): List<RtcpPacket> {
            val end = offset + length
            var cursor = offset
            val out = mutableListOf<RtcpPacket>()
            while (cursor + 4 <= end) {
                val b0 = data[cursor].toInt() and 0xFF
                val version = (b0 ushr 6) and 0x3
                if (version != 2) return emptyList()
                val pt = data[cursor + 1].toInt() and 0xFF
                val lenWords = ((data[cursor + 2].toInt() and 0xFF) shl 8) or
                    (data[cursor + 3].toInt() and 0xFF)
                val packetBytes = (lenWords + 1) * 4
                if (cursor + packetBytes > end) return emptyList()

                if (pt == PT_SR && packetBytes >= 28) {
                    out += parseSenderReport(data, cursor)
                }
                cursor += packetBytes
            }
            return out
        }

        private fun parseSenderReport(data: ByteArray, at: Int): SenderReport {
            val ssrc = readUInt32(data, at + 4)
            val ntpHi = readUInt32(data, at + 8)
            val ntpLo = readUInt32(data, at + 12)
            val ntp = (ntpHi shl 32) or (ntpLo and 0xFFFFFFFFL)
            val rtpTs = readUInt32(data, at + 16)
            val pc = readUInt32(data, at + 20)
            val oc = readUInt32(data, at + 24)
            return SenderReport(
                senderSsrc = ssrc,
                ntpTimestamp = ntp,
                rtpTimestamp = rtpTs,
                senderPacketCount = pc,
                senderOctetCount = oc,
            )
        }

        private fun readUInt32(data: ByteArray, at: Int): Long {
            return ((data[at].toLong() and 0xFF) shl 24) or
                ((data[at + 1].toLong() and 0xFF) shl 16) or
                ((data[at + 2].toLong() and 0xFF) shl 8) or
                (data[at + 3].toLong() and 0xFF)
        }
    }
}
