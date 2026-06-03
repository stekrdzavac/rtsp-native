// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.videodecoder

/**
 * Pulls the coded picture width and height out of an H.265 (HEVC) SPS NAL.
 *
 * We need this at *configure* time. Some hardware decoders — notably the
 * Unisoc/Spreadtrum `OMX.sprd.hevc.decoder` — don't support dynamic output
 * buffers, so they size their surface buffers statically from the MediaFormat
 * dimensions and cannot grow them when the real frame turns out larger. If we
 * configure with the wrong size, those decoders fault before the first frame
 * (`signalError` / black screen). Configuring with the true coded size avoids
 * that.
 *
 * We only walk the SPS as far as pic_width/height_in_luma_samples (the coded
 * size, which is what drives buffer allocation). The conformance window that
 * follows is display cropping; it's smaller-or-equal and gets reported later
 * via MediaCodec's OUTPUT_FORMAT_CHANGED, so we don't need it here.
 *
 * Syntax per H.265 §7.3.2.2 (seq_parameter_set_rbsp) and §7.3.3
 * (profile_tier_level).
 */
object HevcSpsParser {

    /**
     * @param spsNal the SPS bytes WITHOUT the Annex-B start code, but WITH
     *   the 2-byte H.265 NAL unit header.
     */
    fun parse(spsNal: ByteArray): SpsParser.Dimensions? {
        if (spsNal.size < 4) return null
        // startOffset = 2 skips the 2-byte HEVC NAL unit header.
        val reader = NalBitReader(removeEmulationPrevention(spsNal), startOffset = 2)
        return runCatching {
            reader.readBits(4) // sps_video_parameter_set_id
            val maxSubLayersMinus1 = reader.readBits(3) // sps_max_sub_layers_minus1
            reader.readBits(1) // sps_temporal_id_nesting_flag

            skipProfileTierLevel(reader, maxSubLayersMinus1)

            reader.readUe() // sps_seq_parameter_set_id
            val chromaFormatIdc = reader.readUe()
            if (chromaFormatIdc == 3) reader.readBits(1) // separate_colour_plane_flag

            val width = reader.readUe() // pic_width_in_luma_samples
            val height = reader.readUe() // pic_height_in_luma_samples
            if (width <= 0 || height <= 0) return null

            SpsParser.Dimensions(width, height)
        }.getOrNull()
    }

    /**
     * Skip profile_tier_level( profilePresentFlag = 1, maxSubLayersMinus1 ).
     * The general profile/tier/level block is a fixed 96 bits (88 bits of
     * profile/tier + 8-bit general_level_idc). Each present sub-layer adds an
     * 88-bit profile block and/or an 8-bit level.
     */
    private fun skipProfileTierLevel(reader: NalBitReader, maxSubLayersMinus1: Int) {
        reader.skipBits(96)
        if (maxSubLayersMinus1 > 0) {
            val profilePresent = BooleanArray(maxSubLayersMinus1)
            val levelPresent = BooleanArray(maxSubLayersMinus1)
            for (i in 0 until maxSubLayersMinus1) {
                profilePresent[i] = reader.readBits(1) == 1
                levelPresent[i] = reader.readBits(1) == 1
            }
            // reserved_zero_2bits for i in maxSubLayersMinus1..7
            for (i in maxSubLayersMinus1 until 8) reader.readBits(2)
            for (i in 0 until maxSubLayersMinus1) {
                if (profilePresent[i]) reader.skipBits(88)
                if (levelPresent[i]) reader.skipBits(8)
            }
        }
    }
}
