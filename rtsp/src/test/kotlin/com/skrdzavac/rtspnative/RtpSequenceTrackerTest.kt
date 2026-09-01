// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RtpSequenceTrackerTest {

    @Test
    fun `first packet and contiguous packets are not loss`() {
        val t = RtpSequenceTracker()
        assertFalse(t.onPacket(100))
        assertFalse(t.onPacket(101))
        assertFalse(t.onPacket(102))
    }

    @Test
    fun `a skipped sequence number is loss`() {
        val t = RtpSequenceTracker()
        t.onPacket(100)
        assertTrue(t.onPacket(102))
        // Loss is reported once per gap, not on every following packet.
        assertFalse(t.onPacket(103))
        assertTrue(t.onPacket(110))
    }

    @Test
    fun `contiguous packets across the 16-bit wrap are not loss`() {
        val t = RtpSequenceTracker()
        t.onPacket(0xFFFE)
        assertFalse(t.onPacket(0xFFFF))
        assertFalse(t.onPacket(0x0000))
        assertFalse(t.onPacket(0x0001))
    }

    @Test
    fun `a gap across the wrap is loss`() {
        val t = RtpSequenceTracker()
        t.onPacket(0xFFFF)
        assertTrue(t.onPacket(0x0001))
    }

    @Test
    fun `late and duplicate packets are reordering, not loss`() {
        val t = RtpSequenceTracker()
        t.onPacket(100)
        t.onPacket(102)
        assertFalse(t.onPacket(101))
        assertFalse(t.onPacket(102))
        assertFalse(t.onPacket(103))
    }

    @Test
    fun `late packet arriving just before the wrap is not loss`() {
        val t = RtpSequenceTracker()
        t.onPacket(0x0002)
        assertFalse(t.onPacket(0xFFFF))
        assertFalse(t.onPacket(0x0003))
    }
}
