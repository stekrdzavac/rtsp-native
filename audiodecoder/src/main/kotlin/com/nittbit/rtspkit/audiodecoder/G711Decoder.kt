package com.nittbit.rtspkit.audiodecoder

/**
 * G.711 software decoder — the sole exception to the "all decoding via
 * MediaCodec" rule (CLAUDE.md §3). One-byte-per-sample expansion to 16-bit
 * linear PCM via precomputed lookup tables; ~10 lines of work per packet.
 *
 * - μ-law (PCMU, RFC 3551 §4.5.14)
 * - A-law (PCMA, RFC 3551 §4.5.15)
 */
object G711Decoder {

    private val ULAW_TABLE: ShortArray = buildUlawTable()
    private val ALAW_TABLE: ShortArray = buildAlawTable()

    fun decodeUlaw(input: ByteArray, offset: Int = 0, length: Int = input.size - offset): ShortArray {
        val out = ShortArray(length)
        for (i in 0 until length) {
            out[i] = ULAW_TABLE[input[offset + i].toInt() and 0xFF]
        }
        return out
    }

    fun decodeAlaw(input: ByteArray, offset: Int = 0, length: Int = input.size - offset): ShortArray {
        val out = ShortArray(length)
        for (i in 0 until length) {
            out[i] = ALAW_TABLE[input[offset + i].toInt() and 0xFF]
        }
        return out
    }

    /**
     * ITU-T G.711 μ-law decode. Encoded byte is bit-inverted from a
     * sign-magnitude representation:
     *   ~b = SEEEMMMM
     *   sign S, 3-bit exponent E, 4-bit mantissa M
     *   magnitude = ((M << 3) + 0x84) << E - 0x84
     */
    private fun buildUlawTable(): ShortArray {
        val table = ShortArray(256)
        for (i in 0 until 256) {
            val b = i.inv() and 0xFF
            val sign = b and 0x80
            val exponent = (b ushr 4) and 0x07
            val mantissa = b and 0x0F
            var magnitude = ((mantissa shl 3) + 0x84) shl exponent
            magnitude -= 0x84
            table[i] = (if (sign != 0) -magnitude else magnitude).toShort()
        }
        return table
    }

    /**
     * ITU-T G.711 A-law decode. Encoded byte is XOR'd with 0x55 to undo
     * the alternating-bit transmission scrambling, then split into
     * sign, exponent, mantissa as above (with a slightly different shape).
     */
    private fun buildAlawTable(): ShortArray {
        val table = ShortArray(256)
        for (i in 0 until 256) {
            val b = i xor 0x55
            val sign = b and 0x80
            val exponent = (b ushr 4) and 0x07
            val mantissa = b and 0x0F
            var magnitude = (mantissa shl 4) or 0x08
            if (exponent != 0) magnitude = (magnitude or 0x100) shl (exponent - 1)
            table[i] = (if (sign != 0) magnitude else -magnitude).toShort()
        }
        return table
    }
}
