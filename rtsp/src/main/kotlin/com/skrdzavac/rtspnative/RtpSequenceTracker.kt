// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative

/**
 * Detects gaps in a 16-bit RTP sequence number space.
 *
 * Only forward jumps count as loss. Late or duplicate packets (seq at or
 * behind the highest seen, modulo wrap) are ignored: they indicate
 * reordering, which the depacketizer handles, not a missing reference.
 */
internal class RtpSequenceTracker {
    private var highest: Int = -1

    /** Returns true when [seq] reveals that at least one packet was skipped. */
    fun onPacket(seq: Int): Boolean {
        val prev = highest
        if (prev < 0) {
            highest = seq
            return false
        }
        val delta = (seq - prev) and 0xFFFF
        // Anything more than half the space away is a wrapped late packet.
        if (delta == 0 || delta >= 0x8000) return false
        highest = seq
        return delta > 1
    }
}
