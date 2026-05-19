// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.audiodecoder

/**
 * L16 over RTP is 16-bit big-endian PCM (RFC 3551 §4.5.11). AudioTrack
 * accepts platform-native (little-endian) PCM, so we byte-swap on the way
 * out. No actual "decoding" happens here.
 */
object L16Decoder {
    fun toLittleEndianPcm(input: ByteArray, offset: Int = 0, length: Int = input.size - offset): ShortArray {
        val sampleCount = length / 2
        val out = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            val hi = input[offset + i * 2].toInt() and 0xFF
            val lo = input[offset + i * 2 + 1].toInt() and 0xFF
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }
}
