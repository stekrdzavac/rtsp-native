// SPDX-License-Identifier: Apache-2.0

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
        val reader = NalBitReader(removeEmulationPrevention(spsNal), startOffset = 1)
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
                    // High-profile cameras send scaling matrices. We don't use
                    // the values, but we must walk past them to reach the pic
                    // dimensions — bailing here would fall back to a wrong size
                    // and brick static-buffer decoders (e.g. Unisoc) on 2K.
                    val listCount = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until listCount) {
                        val listPresent = reader.readBits(1) == 1
                        if (listPresent) skipScalingList(reader, if (i < 6) 16 else 64)
                    }
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

    /**
     * Walk a scaling_list( ) per H.264 §7.3.2.1.1.1 without retaining values.
     * Reads delta_scale se(v) until the list is exhausted or nextScale hits 0.
     */
    private fun skipScalingList(reader: NalBitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        for (j in 0 until size) {
            if (nextScale != 0) {
                val deltaScale = reader.readSe()
                nextScale = (lastScale + deltaScale + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }
}
