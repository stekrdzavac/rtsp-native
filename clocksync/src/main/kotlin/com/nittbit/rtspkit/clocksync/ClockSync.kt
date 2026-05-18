package com.nittbit.rtspkit.clocksync

import com.nittbit.rtspkit.core.RtcpPacket

/**
 * Anchors RTP timestamps to a monotonic media-time origin so the renderer
 * can schedule presentation. Stage 1 implementation: the first observed
 * packet defines time zero; RTCP Sender Reports re-anchor without
 * resetting wall-clock continuity.
 *
 * Wall-clock A/V sync (using the NTP timestamp inside RTCP SR) is
 * deferred to Stage 2 when audio is added.
 */
class ClockSync(private val clockRateHz: Int) {

    private val unwrapper = RtpTimestampUnwrapper()
    private var anchorRtp: Long = -1
    private var anchorMediaNanos: Long = 0

    /**
     * Returns the presentation time in nanoseconds from media start for
     * the given (32-bit, possibly wrapped) RTP timestamp.
     */
    fun mediaTimeForRtp(rtpTimestamp: Long): Long {
        val unwrapped = unwrapper.unwrap(rtpTimestamp)
        if (anchorRtp < 0) {
            anchorRtp = unwrapped
            anchorMediaNanos = 0
            return 0
        }
        val rtpDelta = unwrapped - anchorRtp
        return anchorMediaNanos + (rtpDelta * 1_000_000_000L) / clockRateHz.coerceAtLeast(1)
    }

    /**
     * Re-anchor against an RTCP Sender Report. The report's RTP timestamp
     * corresponds to the same instant as its NTP timestamp. Stage 1 just
     * keeps the unwrapper consistent — no NTP-based correction yet.
     */
    fun onSenderReport(sr: RtcpPacket.SenderReport) {
        unwrapper.unwrap(sr.rtpTimestamp)
    }

    fun reset() {
        unwrapper.reset()
        anchorRtp = -1
        anchorMediaNanos = 0
    }
}
