package com.nittbit.rtspkit.audiodecoder

import org.junit.Test
import kotlin.test.assertEquals

class G711DecoderTest {

    @Test
    fun `mu-law decodes silence bytes near zero amplitude`() {
        // 0xFF is μ-law "0" (silence); maps to PCM 0.
        val pcm = G711Decoder.decodeUlaw(byteArrayOf(0xFF.toByte()))
        assertEquals(0, pcm[0].toInt())
    }

    @Test
    fun `mu-law decode is sign-symmetric for 0x7F vs 0xFF`() {
        // 0x7F has the sign bit set (after inversion → 0x80), so it
        // produces the most-negative side; 0xFF (silence) produces 0.
        val pcmPositive = G711Decoder.decodeUlaw(byteArrayOf(0x7F.toByte()))
        assertEquals(0, pcmPositive[0].toInt())
    }

    @Test
    fun `a-law decodes silence bytes near zero amplitude`() {
        // 0xD5 is A-law "0".
        val pcm = G711Decoder.decodeAlaw(byteArrayOf(0xD5.toByte()))
        // A-law table maps 0xD5 to exactly 8 (the minimum quantization step).
        assertEquals(8, pcm[0].toInt())
    }

    @Test
    fun `mu-law and a-law roundtrip 256 byte values without crashing`() {
        val all = ByteArray(256) { it.toByte() }
        val u = G711Decoder.decodeUlaw(all)
        val a = G711Decoder.decodeAlaw(all)
        assertEquals(256, u.size)
        assertEquals(256, a.size)
    }
}
