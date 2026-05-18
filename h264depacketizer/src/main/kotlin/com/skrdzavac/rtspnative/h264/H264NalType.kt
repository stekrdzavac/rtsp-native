package com.skrdzavac.rtspnative.h264

/**
 * H.264 NAL unit types relevant to RTP depacketization (RFC 6184).
 */
object H264NalType {
    const val SLICE_NON_IDR = 1
    const val SLICE_IDR = 5
    const val SPS = 7
    const val PPS = 8
    const val SEI = 6
    const val AUD = 9
    const val STAP_A = 24
    const val STAP_B = 25
    const val MTAP_16 = 26
    const val MTAP_24 = 27
    const val FU_A = 28
    const val FU_B = 29
}
