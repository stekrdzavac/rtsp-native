// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.videodecoder

/**
 * MSB-first bit writer with Exp-Golomb support, for building SPS test
 * vectors. Written independently of the production [NalBitReader] so a shared
 * mistake can't make encode and decode agree on the wrong answer.
 */
internal class BitWriter {
    private val bits = ArrayList<Int>()

    fun writeBits(value: Int, n: Int) {
        for (i in n - 1 downTo 0) bits.add((value ushr i) and 1)
    }

    /** Unsigned Exp-Golomb ue(v). */
    fun writeUe(value: Int) {
        val code = value + 1
        val len = 32 - Integer.numberOfLeadingZeros(code)
        repeat(len - 1) { bits.add(0) }
        writeBits(code, len)
    }

    /** Signed Exp-Golomb se(v). */
    fun writeSe(value: Int) {
        writeUe(if (value > 0) 2 * value - 1 else -2 * value)
    }

    fun toByteArray(): ByteArray {
        val pad = (8 - bits.size % 8) % 8
        repeat(pad) { bits.add(0) }
        val out = ByteArray(bits.size / 8)
        for (i in bits.indices) {
            if (bits[i] == 1) {
                out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            }
        }
        return out
    }
}

/** Inverse of [removeEmulationPrevention]: insert 0x03 into 00 00 0x runs. */
internal fun addEmulationPrevention(input: ByteArray): ByteArray {
    val out = ArrayList<Byte>(input.size + 4)
    var zeros = 0
    for (b in input) {
        if (zeros >= 2 && (b.toInt() and 0xFC) == 0) {
            out.add(3)
            zeros = 0
        }
        out.add(b)
        zeros = if (b.toInt() == 0) zeros + 1 else 0
    }
    return out.toByteArray()
}
