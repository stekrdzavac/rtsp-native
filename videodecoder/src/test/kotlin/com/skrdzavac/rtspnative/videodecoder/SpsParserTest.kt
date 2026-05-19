// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.videodecoder

import org.junit.Test
import kotlin.test.assertNull

class SpsParserTest {

    /**
     * Real-camera SPS test vectors require a packet capture, which we don't
     * have in this repo yet. End-to-end SPS parsing is verified by the
     * sample-app smoke test against a live camera. Here we just check the
     * parser doesn't crash on a clearly invalid input.
     */
    @Test
    fun `returns null for trivially invalid SPS`() {
        assertNull(SpsParser.parse(byteArrayOf(0x67.toByte())))
        assertNull(SpsParser.parse(byteArrayOf()))
    }
}
