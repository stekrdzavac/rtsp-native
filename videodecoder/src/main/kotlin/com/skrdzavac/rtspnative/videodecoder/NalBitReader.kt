// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.videodecoder

/**
 * Strip 0x000003 emulation-prevention bytes from an RBSP payload
 * (H.264 §7.4.1 / H.265 §7.4.2). Shared by the H.264 and H.265 SPS parsers.
 */
internal fun removeEmulationPrevention(input: ByteArray): ByteArray {
    val out = ByteArray(input.size)
    var w = 0
    var i = 0
    while (i < input.size) {
        if (i + 2 < input.size && input[i] == 0.toByte() && input[i + 1] == 0.toByte() && input[i + 2] == 3.toByte()) {
            out[w++] = 0
            out[w++] = 0
            i += 3
        } else {
            out[w++] = input[i++]
        }
    }
    return out.copyOf(w)
}

/**
 * Minimal MSB-first bit reader with Exp-Golomb support, for walking SPS
 * RBSP fields. Reads past the end throw via [error] so callers can wrap the
 * whole parse in `runCatching`.
 */
internal class NalBitReader(private val data: ByteArray, startOffset: Int = 0) {
    private var bytePos = startOffset
    private var bitPos = 0

    fun readBits(n: Int): Int {
        var value = 0
        repeat(n) {
            if (bytePos >= data.size) error("eof")
            val bit = (data[bytePos].toInt() ushr (7 - bitPos)) and 1
            value = (value shl 1) or bit
            bitPos++
            if (bitPos == 8) {
                bitPos = 0
                bytePos++
            }
        }
        return value
    }

    /** Advance [n] bits without materializing a value (n may exceed 32). */
    fun skipBits(n: Int) {
        repeat(n) {
            if (bytePos >= data.size) error("eof")
            bitPos++
            if (bitPos == 8) {
                bitPos = 0
                bytePos++
            }
        }
    }

    fun readUe(): Int {
        var zeros = 0
        while (readBits(1) == 0) {
            zeros++
            if (zeros > 32) error("invalid ue")
        }
        if (zeros == 0) return 0
        val suffix = readBits(zeros)
        return (1 shl zeros) - 1 + suffix
    }

    fun readSe(): Int {
        val ue = readUe()
        return if (ue and 1 == 1) (ue + 1) / 2 else -(ue / 2)
    }
}
