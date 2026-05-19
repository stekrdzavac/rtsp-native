// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.clocksync

/**
 * Unwraps a 32-bit RTP timestamp into a 64-bit monotonically increasing
 * value, tolerating the 0x100000000 wraparound. Long-running camera
 * streams exceed 32-bit timestamps in ~13 hours at 90 kHz; without
 * unwrapping, time would jump backwards by ~13 hours at the wrap.
 */
class RtpTimestampUnwrapper {
    private var lastWrapped: Long = -1
    private var epochs: Long = 0

    fun unwrap(rtpTimestamp32: Long): Long {
        val ts = rtpTimestamp32 and 0xFFFFFFFFL
        if (lastWrapped < 0) {
            lastWrapped = ts
            return ts
        }
        val lastLow = lastWrapped and 0xFFFFFFFFL
        // Treat a backwards jump of more than half the 32-bit range as a wrap.
        if (ts < lastLow && lastLow - ts > 0x80000000L) {
            epochs++
        } else if (ts > lastLow && ts - lastLow > 0x80000000L) {
            // Sequence number went backwards (out-of-order packet near a wrap)
            // — back-correct by one epoch.
            return (epochs - 1) * 0x100000000L + ts
        }
        lastWrapped = ts
        return epochs * 0x100000000L + ts
    }

    fun reset() {
        lastWrapped = -1
        epochs = 0
    }
}
