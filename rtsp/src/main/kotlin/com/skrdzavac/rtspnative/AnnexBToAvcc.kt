// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative

/**
 * Converts an Annex-B byte stream (the depacketizer's output: each NAL
 * prefixed with `00 00 00 01`) into AVCC / HVCC format (each NAL prefixed
 * with a 4-byte big-endian length). MP4 / MediaMuxer expects the latter
 * for video samples.
 *
 * Our depacketizers always emit 4-byte start codes, so the parser only
 * needs to handle that shape — no 3-byte fallback. Within a NAL payload,
 * H.264/H.265 use emulation-prevention bytes (`00 00 03`) so the raw
 * `00 00 00 01` pattern can't appear inside a real NAL.
 */
internal object AnnexBToAvcc {

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    fun convert(annexB: ByteArray): ByteArray {
        val nalRanges = splitNals(annexB)
        var total = 0
        for (range in nalRanges) total += 4 + (range.last - range.first + 1)
        val out = ByteArray(total)
        var pos = 0
        for (range in nalRanges) {
            val len = range.last - range.first + 1
            out[pos] = (len ushr 24).toByte()
            out[pos + 1] = (len ushr 16).toByte()
            out[pos + 2] = (len ushr 8).toByte()
            out[pos + 3] = len.toByte()
            pos += 4
            System.arraycopy(annexB, range.first, out, pos, len)
            pos += len
        }
        return out
    }

    private fun splitNals(annexB: ByteArray): List<IntRange> {
        val nals = mutableListOf<IntRange>()
        var i = 0
        while (i < annexB.size) {
            if (i + 4 > annexB.size) break
            if (!matchesStartCode(annexB, i)) {
                i++
                continue
            }
            val nalStart = i + START_CODE.size
            var j = nalStart
            while (j + 4 <= annexB.size) {
                if (matchesStartCode(annexB, j)) break
                j++
            }
            val nalEndExclusive = if (j + 4 > annexB.size && !matchesStartCode(annexB, j)) annexB.size else j
            if (nalEndExclusive > nalStart) {
                nals += nalStart until nalEndExclusive
            }
            i = nalEndExclusive
        }
        return nals
    }

    private fun matchesStartCode(data: ByteArray, offset: Int): Boolean {
        if (offset + 4 > data.size) return false
        return data[offset] == 0.toByte() &&
            data[offset + 1] == 0.toByte() &&
            data[offset + 2] == 0.toByte() &&
            data[offset + 3] == 1.toByte()
    }
}
