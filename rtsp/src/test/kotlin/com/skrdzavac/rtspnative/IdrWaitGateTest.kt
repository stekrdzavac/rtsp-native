// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdrWaitGateTest {

    @Test
    fun `unarmed gate passes everything`() {
        val gate = IdrWaitGate()
        assertTrue(gate.shouldPass(isKeyframe = false))
        assertTrue(gate.shouldPass(isKeyframe = true))
        assertFalse(gate.awaitingKeyframe)
    }

    @Test
    fun `armed gate drops non-keyframes until first keyframe`() {
        val gate = IdrWaitGate()
        gate.arm()
        assertTrue(gate.awaitingKeyframe)

        assertFalse(gate.shouldPass(isKeyframe = false))
        assertFalse(gate.shouldPass(isKeyframe = false))
        assertFalse(gate.shouldPass(isKeyframe = false))
        assertTrue(gate.awaitingKeyframe)

        // First keyframe passes and disarms the gate.
        assertTrue(gate.shouldPass(isKeyframe = true))
        assertFalse(gate.awaitingKeyframe)

        // Subsequent frames pass regardless of keyframe status.
        assertTrue(gate.shouldPass(isKeyframe = false))
        assertTrue(gate.shouldPass(isKeyframe = false))
        assertTrue(gate.shouldPass(isKeyframe = true))
    }

    @Test
    fun `re-arming after disarm waits for another keyframe`() {
        val gate = IdrWaitGate()
        gate.arm()
        gate.shouldPass(isKeyframe = true)  // disarms

        gate.arm()
        assertTrue(gate.awaitingKeyframe)
        assertFalse(gate.shouldPass(isKeyframe = false))
        assertTrue(gate.shouldPass(isKeyframe = true))
        assertFalse(gate.awaitingKeyframe)
    }

    @Test
    fun `arming an already-armed gate is idempotent`() {
        val gate = IdrWaitGate()
        gate.arm()
        gate.arm()
        gate.arm()
        assertTrue(gate.awaitingKeyframe)
        assertFalse(gate.shouldPass(isKeyframe = false))
        assertTrue(gate.shouldPass(isKeyframe = true))
    }
}
