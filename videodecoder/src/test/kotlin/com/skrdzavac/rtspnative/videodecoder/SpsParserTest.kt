// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.videodecoder

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpsParserTest {

    @Test
    fun `parses baseline profile resolution`() {
        val sps = buildH264Sps(width = 1280, height = 720, profileIdc = 66)
        assertEquals(SpsParser.Dimensions(1280, 720), SpsParser.parse(sps))
    }

    @Test
    fun `parses high profile without scaling matrix`() {
        val sps = buildH264Sps(width = 1920, height = 1088, profileIdc = 100)
        assertEquals(SpsParser.Dimensions(1920, 1088), SpsParser.parse(sps))
    }

    /**
     * Regression: high-profile 2K cameras often carry a seq_scaling_matrix.
     * The parser used to bail to a 1920x1080 fallback here, which bricks
     * static-buffer decoders (e.g. Unisoc) on 2K. It must now skip the
     * scaling lists and reach the real dimensions.
     */
    @Test
    fun `parses high profile with scaling matrix`() {
        val sps = buildH264Sps(width = 2560, height = 1440, profileIdc = 100, withScalingMatrix = true)
        assertEquals(SpsParser.Dimensions(2560, 1440), SpsParser.parse(sps))
    }

    @Test
    fun `returns null for trivially invalid SPS`() {
        assertNull(SpsParser.parse(byteArrayOf(0x67.toByte())))
        assertNull(SpsParser.parse(byteArrayOf()))
    }

    /**
     * Builds an H.264 SPS NAL (1-byte header + RBSP through frame_mbs_only_flag),
     * emulation-prevention encoded. Assumes frame_mbs_only (progressive) and
     * 4:2:0, so coded width/height are multiples of 16.
     */
    private fun buildH264Sps(
        width: Int,
        height: Int,
        profileIdc: Int,
        withScalingMatrix: Boolean = false,
    ): ByteArray {
        val w = BitWriter()
        // NAL header: forbidden_zero(1)=0, nal_ref_idc(2)=3, nal_unit_type(5)=7 (SPS).
        w.writeBits(0, 1)
        w.writeBits(3, 2)
        w.writeBits(7, 5)

        w.writeBits(profileIdc, 8)
        w.writeBits(0, 8) // constraint_set flags + reserved
        w.writeBits(41, 8) // level_idc
        w.writeUe(0) // seq_parameter_set_id

        val highProfile = profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128)
        if (highProfile) {
            w.writeUe(1) // chroma_format_idc = 4:2:0
            w.writeUe(0) // bit_depth_luma_minus8
            w.writeUe(0) // bit_depth_chroma_minus8
            w.writeBits(0, 1) // qpprime_y_zero_transform_bypass_flag
            if (withScalingMatrix) {
                w.writeBits(1, 1) // seq_scaling_matrix_present_flag
                // 8 lists (chroma != 3). Exercise both scaling_list branches:
                // list 0 runs the full 16 entries, list 1 stops early.
                w.writeBits(1, 1) // list 0 present
                repeat(16) { w.writeSe(0) } // all deltas 0 → reads every entry
                w.writeBits(1, 1) // list 1 present
                w.writeSe(-8) // nextScale -> 0 → list terminates after one entry
                repeat(6) { w.writeBits(0, 1) } // lists 2..7 absent
            } else {
                w.writeBits(0, 1) // seq_scaling_matrix_present_flag
            }
        }

        w.writeUe(0) // log2_max_frame_num_minus4
        w.writeUe(0) // pic_order_cnt_type
        w.writeUe(0) // log2_max_pic_order_cnt_lsb_minus4
        w.writeUe(1) // num_ref_frames
        w.writeBits(0, 1) // gaps_in_frame_num_value_allowed_flag
        w.writeUe(width / 16 - 1) // pic_width_in_mbs_minus1
        w.writeUe(height / 16 - 1) // pic_height_in_map_units_minus1
        w.writeBits(1, 1) // frame_mbs_only_flag

        return addEmulationPrevention(w.toByteArray())
    }
}
