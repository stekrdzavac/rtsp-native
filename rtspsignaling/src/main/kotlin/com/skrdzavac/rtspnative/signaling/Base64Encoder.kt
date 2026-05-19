// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.signaling

/**
 * Tiny RFC 4648 Base64 encoder. Self-contained to avoid `java.util.Base64`
 * desugaring concerns at minSdk 24.
 */
internal object Base64Encoder {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        while (i + 3 <= data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = data[i + 1].toInt() and 0xFF
            val b2 = data[i + 2].toInt() and 0xFF
            out.append(ALPHABET[(b0 ushr 2) and 0x3F])
            out.append(ALPHABET[((b0 shl 4) or (b1 ushr 4)) and 0x3F])
            out.append(ALPHABET[((b1 shl 2) or (b2 ushr 6)) and 0x3F])
            out.append(ALPHABET[b2 and 0x3F])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val b0 = data[i].toInt() and 0xFF
                out.append(ALPHABET[(b0 ushr 2) and 0x3F])
                out.append(ALPHABET[(b0 shl 4) and 0x3F])
                out.append("==")
            }
            2 -> {
                val b0 = data[i].toInt() and 0xFF
                val b1 = data[i + 1].toInt() and 0xFF
                out.append(ALPHABET[(b0 ushr 2) and 0x3F])
                out.append(ALPHABET[((b0 shl 4) or (b1 ushr 4)) and 0x3F])
                out.append(ALPHABET[(b1 shl 2) and 0x3F])
                out.append("=")
            }
        }
        return out.toString()
    }
}
