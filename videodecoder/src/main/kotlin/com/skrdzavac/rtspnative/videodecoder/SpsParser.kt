package com.skrdzavac.rtspnative.videodecoder

/**
 * Pulls picture width and height out of an H.264 SPS NAL.
 *
 * The SPS is bit-packed Exp-Golomb encoded — this is a stripped-down
 * parser that just walks the SPS up to pic_width_in_mbs_minus1 and
 * pic_height_in_map_units_minus1.
 */
object SpsParser {

    data class Dimensions(val width: Int, val height: Int)

    /**
     * @param spsNal the SPS bytes WITHOUT the Annex-B start code, but
     *   WITH the 1-byte NAL header (0x67 / 0x47 / etc).
     */
    fun parse(spsNal: ByteArray): Dimensions? {
        if (spsNal.size < 4) return null
        val reader = BitReader(removeEmulationPrevention(spsNal), startOffset = 1)
        return runCatching {
            val profileIdc = reader.readBits(8)
            reader.readBits(8) // constraint_set + reserved
            reader.readBits(8) // level_idc

            reader.readUe() // seq_parameter_set_id

            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 ||
                profileIdc == 244 || profileIdc == 44 || profileIdc == 83 ||
                profileIdc == 86 || profileIdc == 118 || profileIdc == 128
            ) {
                val chromaFormatIdc = reader.readUe()
                if (chromaFormatIdc == 3) reader.readBits(1) // separate_colour_plane_flag
                reader.readUe() // bit_depth_luma_minus8
                reader.readUe() // bit_depth_chroma_minus8
                reader.readBits(1) // qpprime_y_zero_transform_bypass_flag
                val seqScalingMatrixPresent = reader.readBits(1) == 1
                if (seqScalingMatrixPresent) {
                    // Stage 1: we don't actually need to walk the scaling matrix.
                    // Skip it crudely by bailing — fallback used elsewhere.
                    return null
                }
            }

            reader.readUe() // log2_max_frame_num_minus4
            val picOrderCntType = reader.readUe()
            when (picOrderCntType) {
                0 -> reader.readUe() // log2_max_pic_order_cnt_lsb_minus4
                1 -> {
                    reader.readBits(1) // delta_pic_order_always_zero_flag
                    reader.readSe() // offset_for_non_ref_pic
                    reader.readSe() // offset_for_top_to_bottom_field
                    val cycle = reader.readUe()
                    repeat(cycle) { reader.readSe() }
                }
            }

            reader.readUe() // num_ref_frames
            reader.readBits(1) // gaps_in_frame_num_value_allowed_flag
            val picWidthInMbsMinus1 = reader.readUe()
            val picHeightInMapUnitsMinus1 = reader.readUe()
            val frameMbsOnly = reader.readBits(1) == 1

            val width = (picWidthInMbsMinus1 + 1) * 16
            val height = (2 - (if (frameMbsOnly) 1 else 0)) * (picHeightInMapUnitsMinus1 + 1) * 16

            Dimensions(width, height)
        }.getOrNull()
    }

    /** Strip 0x000003 emulation prevention bytes per H.264 §7.4.1. */
    private fun removeEmulationPrevention(input: ByteArray): ByteArray {
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

    private class BitReader(private val data: ByteArray, startOffset: Int = 0) {
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
}
