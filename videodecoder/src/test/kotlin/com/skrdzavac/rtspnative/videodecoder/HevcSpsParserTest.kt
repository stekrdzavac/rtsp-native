// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.videodecoder

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HevcSpsParserTest {

    @Test
    fun `parses 2K coded resolution`() {
        val sps = buildHevcSps(width = 2560, height = 1440)
        assertEquals(SpsParser.Dimensions(2560, 1440), HevcSpsParser.parse(sps))
    }

    @Test
    fun `parses 1080p coded resolution`() {
        val sps = buildHevcSps(width = 1920, height = 1088)
        assertEquals(SpsParser.Dimensions(1920, 1088), HevcSpsParser.parse(sps))
    }

    @Test
    fun `parses 4K coded resolution`() {
        val sps = buildHevcSps(width = 3840, height = 2160)
        assertEquals(SpsParser.Dimensions(3840, 2160), HevcSpsParser.parse(sps))
    }

    @Test
    fun `handles temporal sub-layers in profile_tier_level`() {
        val sps = buildHevcSps(width = 2560, height = 1440, maxSubLayersMinus1 = 2)
        assertEquals(SpsParser.Dimensions(2560, 1440), HevcSpsParser.parse(sps))
    }

    @Test
    fun `returns null for trivially invalid SPS`() {
        assertNull(HevcSpsParser.parse(byteArrayOf(0x42.toByte())))
        assertNull(HevcSpsParser.parse(byteArrayOf()))
    }

    /**
     * Builds a minimal but spec-valid H.265 SPS NAL (2-byte header + RBSP up
     * to pic_height_in_luma_samples), emulation-prevention encoded like a real
     * stream. Written independently of the parser so a shared mistake can't
     * make both agree. The zeroed 96-bit profile_tier_level guarantees zero
     * runs, so every vector exercises the 0x000003 strip on the way back in.
     */
    private fun buildHevcSps(
        width: Int,
        height: Int,
        maxSubLayersMinus1: Int = 0,
        chromaFormatIdc: Int = 1,
    ): ByteArray {
        val w = BitWriter()
        // 2-byte HEVC NAL unit header: forbidden_zero(1)=0, nal_unit_type(6)=33
        // (SPS_NUT), nuh_layer_id(6)=0, nuh_temporal_id_plus1(3)=1.
        w.writeBits(0, 1)
        w.writeBits(33, 6)
        w.writeBits(0, 6)
        w.writeBits(1, 3)

        w.writeBits(0, 4) // sps_video_parameter_set_id
        w.writeBits(maxSubLayersMinus1, 3)
        w.writeBits(1, 1) // sps_temporal_id_nesting_flag

        // profile_tier_level( 1, maxSubLayersMinus1 ): general block = 96 bits.
        repeat(96) { w.writeBits(0, 1) }
        if (maxSubLayersMinus1 > 0) {
            // All sub-layer profile/level present flags = 0 → no extra blocks,
            // just the reserved_zero_2bits padding to 8 entries.
            for (i in 0 until maxSubLayersMinus1) {
                w.writeBits(0, 1) // sub_layer_profile_present_flag
                w.writeBits(0, 1) // sub_layer_level_present_flag
            }
            for (i in maxSubLayersMinus1 until 8) w.writeBits(0, 2)
        }

        w.writeUe(0) // sps_seq_parameter_set_id
        w.writeUe(chromaFormatIdc)
        if (chromaFormatIdc == 3) w.writeBits(0, 1)
        w.writeUe(width) // pic_width_in_luma_samples
        w.writeUe(height) // pic_height_in_luma_samples

        return addEmulationPrevention(w.toByteArray())
    }
}
