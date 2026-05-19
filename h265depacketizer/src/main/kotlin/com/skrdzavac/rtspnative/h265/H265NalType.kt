// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.h265

/**
 * H.265 (HEVC) NAL unit types relevant to RTP depacketization (RFC 7798).
 * In H.265 the NAL header is 2 bytes:
 *
 *   +---------------+---------------+
 *   |0|1|2|3|4|5|6|7|0|1|2|3|4|5|6|7|
 *   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *   |F|   Type    |  LayerId  | TID |
 *   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * Type is 6 bits (vs 5 in H.264) and lives in bits 1–6 of the first byte.
 */
object H265NalType {
    // Keyframe (IRAP) slice NAL types — Intra Random Access Point pictures
    const val BLA_W_LP = 16
    const val BLA_W_RADL = 17
    const val BLA_N_LP = 18
    const val IDR_W_RADL = 19
    const val IDR_N_LP = 20
    const val CRA_NUT = 21

    const val VPS_NUT = 32
    const val SPS_NUT = 33
    const val PPS_NUT = 34

    // RTP-specific NAL types (only valid as RTP payload header)
    const val AP = 48  // Aggregation Packet
    const val FU = 49  // Fragmentation Unit
    const val PACI = 50 // PAyload Content Information — Stage 2 doesn't support

    /** True if [type] is an IRAP (random-access keyframe) slice. */
    fun isKeyframe(type: Int): Boolean = type in BLA_W_LP..CRA_NUT

    /** Extract the NAL unit type from the first byte of a 2-byte H.265 NAL header. */
    fun typeOf(headerByte0: Int): Int = (headerByte0 ushr 1) and 0x3F
}
