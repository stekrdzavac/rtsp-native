package com.nittbit.rtspkit.h264

import java.io.ByteArrayOutputStream

/**
 * H.264 NAL unit transport prefixes. The Annex-B byte stream prepends each
 * NAL with `00 00 00 01` — that's what `MediaCodec.createDecoderByType("video/avc")`
 * expects in its input buffers.
 */
object AnnexB {
    val START_CODE: ByteArray = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    fun writeNal(out: ByteArrayOutputStream, nal: ByteArray, offset: Int = 0, length: Int = nal.size - offset) {
        out.write(START_CODE)
        out.write(nal, offset, length)
    }
}
