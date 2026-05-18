package com.skrdzavac.rtspnative.audiodepacketizer

import com.skrdzavac.rtspnative.core.AccessUnit
import com.skrdzavac.rtspnative.core.RtpPacket

/**
 * RFC 3640 §3.3 (mpeg4-generic) AAC depacketizer.
 *
 * Each RTP payload begins with the AU-headers section:
 *   bits 0..15   — AU-headers-length (size in BITS of the AU-headers
 *                  section that follows, NOT counting this field)
 *   AU-headers   — one per AU, packed bit-fields:
 *                    AU-size:         [sizeLength] bits
 *                    AU-index-delta:  [indexDeltaLength] bits   (first
 *                                     header carries AU-index instead)
 *   [padding to next byte boundary]
 *   AU data      — concatenated, in the order given by the headers
 *
 * Field widths come from SDP fmtp parameters `sizeLength` and
 * `indexDeltaLength` (defaults 13/3, the most common shape).
 *
 * Stage 2 supports only non-fragmented AUs: AAC AUs ≤ ~1.4 KB so a
 * single RTP packet holds the entire AU in practice. Camera audio
 * bitrates rarely exceed this.
 */
class AacDepacketizer(
    private val sizeLength: Int = 13,
    private val indexDeltaLength: Int = 3,
    private val ctsDeltaLength: Int = 0,
    private val dtsDeltaLength: Int = 0,
) : AudioDepacketizer {

    override fun depacketize(packet: RtpPacket): List<AccessUnit.Audio> {
        if (packet.payloadLength < 2) return emptyList()
        val data = packet.buffer
        val offset = packet.payloadOffset
        val end = offset + packet.payloadLength

        val auHeadersLengthBits = ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)
        val auHeadersBytes = (auHeadersLengthBits + 7) / 8
        if (offset + 2 + auHeadersBytes > end) return emptyList()

        val headerStart = offset + 2
        val auDataStart = headerStart + auHeadersBytes

        val headerBitsPerAu = sizeLength + indexDeltaLength + ctsDeltaLength + dtsDeltaLength
        if (headerBitsPerAu == 0) return emptyList()
        val auCount = auHeadersLengthBits / headerBitsPerAu

        val reader = BitReader(data, headerStart * 8)
        val sizes = IntArray(auCount)
        for (i in 0 until auCount) {
            sizes[i] = reader.readBits(sizeLength)
            // AU-index (i=0) or AU-index-delta (i>0); we don't use it in Stage 2.
            if (indexDeltaLength > 0) reader.readBits(indexDeltaLength)
            if (ctsDeltaLength > 0) reader.readBits(ctsDeltaLength)
            if (dtsDeltaLength > 0) reader.readBits(dtsDeltaLength)
        }

        val units = ArrayList<AccessUnit.Audio>(auCount)
        var cursor = auDataStart
        val baseTs = packet.timestamp
        for (i in 0 until auCount) {
            val size = sizes[i]
            if (cursor + size > end) return units
            units += AccessUnit.Audio(
                // All AUs in one RTP packet share a timestamp at the
                // protocol level; Stage 2 leaves CTS-delta unhandled.
                ptsRtp = baseTs,
                payload = data.copyOfRange(cursor, cursor + size),
            )
            cursor += size
        }
        return units
    }

    private class BitReader(private val data: ByteArray, startBit: Int) {
        private var bytePos = startBit / 8
        private var bitPos = startBit % 8

        fun readBits(n: Int): Int {
            var v = 0
            repeat(n) {
                val bit = (data[bytePos].toInt() ushr (7 - bitPos)) and 1
                v = (v shl 1) or bit
                bitPos++
                if (bitPos == 8) {
                    bitPos = 0
                    bytePos++
                }
            }
            return v
        }
    }
}
