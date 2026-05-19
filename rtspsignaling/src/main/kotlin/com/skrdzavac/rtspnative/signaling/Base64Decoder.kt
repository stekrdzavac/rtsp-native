// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.signaling

internal object Base64Decoder {
    private val INDEX = IntArray(128) { -1 }.also {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for (i in alphabet.indices) it[alphabet[i].code] = i
    }

    fun decode(text: String): ByteArray {
        val s = text.trim().filter { it != '\n' && it != '\r' && it != ' ' }
        val padded = s.trimEnd('=')
        val out = ByteArray((padded.length * 6) / 8)
        var buffer = 0
        var bits = 0
        var outPos = 0
        for (c in padded) {
            val v = if (c.code < 128) INDEX[c.code] else -1
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outPos++] = ((buffer ushr bits) and 0xFF).toByte()
            }
        }
        return if (outPos == out.size) out else out.copyOf(outPos)
    }
}
