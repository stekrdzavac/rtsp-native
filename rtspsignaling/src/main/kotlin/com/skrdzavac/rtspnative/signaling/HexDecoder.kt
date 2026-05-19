// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.signaling

internal object HexDecoder {
    fun decode(hex: String): ByteArray {
        val clean = hex.filter { it != ' ' && it != '\n' && it != '\r' }
        val len = clean.length / 2
        val out = ByteArray(len)
        for (i in 0 until len) {
            val hi = clean[i * 2].digitToInt(16)
            val lo = clean[i * 2 + 1].digitToInt(16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
