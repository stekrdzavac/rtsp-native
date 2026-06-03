// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.videodecoder

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChooseDecoderTest {

    private val hw = DecoderOption("hw.dec", hardware = true, supportsSize = true)
    private val hwTooSmall = DecoderOption("hw.dec", hardware = true, supportsSize = false)
    private val sw = DecoderOption("sw.dec", hardware = false, supportsSize = true)
    private val swTooSmall = DecoderOption("sw.dec", hardware = false, supportsSize = false)

    @Test
    fun `prefers capable hardware decoder by default`() {
        assertEquals("hw.dec", chooseDecoder(listOf(sw, hw), preferSoftware = false))
    }

    @Test
    fun `falls back to capable software when hardware cannot do the size`() {
        assertEquals("sw.dec", chooseDecoder(listOf(hwTooSmall, sw), preferSoftware = false))
    }

    @Test
    fun `prefers software when asked`() {
        assertEquals("sw.dec", chooseDecoder(listOf(hw, sw), preferSoftware = true))
    }

    @Test
    fun `prefer-software still uses hardware if it is the only capable one`() {
        assertEquals("hw.dec", chooseDecoder(listOf(hw), preferSoftware = true))
    }

    @Test
    fun `when nothing advertises the size, tries software anyway over hardware`() {
        // The SM-X200 case: HW and SW both report unsupported. Trying SW (which
        // decodes beyond its advertised level) beats re-trying the faulting HW.
        assertEquals("sw.dec", chooseDecoder(listOf(hwTooSmall, swTooSmall), preferSoftware = false))
    }

    @Test
    fun `uses hardware as last resort when no software decoder exists`() {
        assertEquals("hw.dec", chooseDecoder(listOf(hwTooSmall), preferSoftware = false))
    }

    @Test
    fun `returns null only when there are no decoders at all`() {
        assertNull(chooseDecoder(emptyList(), preferSoftware = true))
    }
}
