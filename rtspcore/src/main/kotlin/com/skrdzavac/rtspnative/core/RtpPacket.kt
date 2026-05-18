package com.skrdzavac.rtspnative.core

/**
 * Parsed RTP packet per RFC 3550. Field values keep the wire-format width
 * (timestamp and SSRC are 32-bit unsigned, stored as Long).
 *
 * Payload bytes are not copied — call [copyPayload] when an owned copy is
 * needed. The underlying [buffer] must outlive uses of [payloadOffset]/
 * [payloadLength] reads.
 */
class RtpPacket internal constructor(
    val payloadType: Int,
    val marker: Boolean,
    val sequenceNumber: Int,
    val timestamp: Long,
    val ssrc: Long,
    val csrcs: LongArray,
    val hasExtension: Boolean,
    val extensionProfile: Int,
    val buffer: ByteArray,
    val payloadOffset: Int,
    val payloadLength: Int,
) {
    fun copyPayload(): ByteArray =
        buffer.copyOfRange(payloadOffset, payloadOffset + payloadLength)

    companion object {
        private const val HEADER_MIN = 12

        fun parse(
            data: ByteArray,
            offset: Int = 0,
            length: Int = data.size - offset,
        ): RtpPacket? {
            if (length < HEADER_MIN) return null
            val b0 = data[offset].toInt() and 0xFF
            val version = (b0 ushr 6) and 0x3
            if (version != 2) return null
            val padding = (b0 and 0x20) != 0
            val extension = (b0 and 0x10) != 0
            val cc = b0 and 0x0F

            val b1 = data[offset + 1].toInt() and 0xFF
            val marker = (b1 and 0x80) != 0
            val payloadType = b1 and 0x7F

            val sequenceNumber = ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
            val timestamp = readUInt32(data, offset + 4)
            val ssrc = readUInt32(data, offset + 8)

            var cursor = offset + HEADER_MIN
            val end = offset + length

            if (cursor + cc * 4 > end) return null
            val csrcs = LongArray(cc) { i ->
                readUInt32(data, cursor + i * 4)
            }
            cursor += cc * 4

            var extensionProfile = 0
            if (extension) {
                if (cursor + 4 > end) return null
                extensionProfile = ((data[cursor].toInt() and 0xFF) shl 8) or
                    (data[cursor + 1].toInt() and 0xFF)
                val extLenWords = ((data[cursor + 2].toInt() and 0xFF) shl 8) or
                    (data[cursor + 3].toInt() and 0xFF)
                cursor += 4
                val extBytes = extLenWords * 4
                if (cursor + extBytes > end) return null
                cursor += extBytes
            }

            var payloadEnd = end
            if (padding) {
                if (payloadEnd <= cursor) return null
                val padLen = data[payloadEnd - 1].toInt() and 0xFF
                if (padLen == 0 || padLen > payloadEnd - cursor) return null
                payloadEnd -= padLen
            }

            return RtpPacket(
                payloadType = payloadType,
                marker = marker,
                sequenceNumber = sequenceNumber,
                timestamp = timestamp,
                ssrc = ssrc,
                csrcs = csrcs,
                hasExtension = extension,
                extensionProfile = extensionProfile,
                buffer = data,
                payloadOffset = cursor,
                payloadLength = payloadEnd - cursor,
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
