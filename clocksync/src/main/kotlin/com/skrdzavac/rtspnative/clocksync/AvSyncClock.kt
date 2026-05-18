package com.skrdzavac.rtspnative.clocksync

import com.skrdzavac.rtspnative.core.RtcpPacket

/**
 * A/V synchronization clock backed by RTCP Sender Reports.
 *
 * For each track, an SR gives us a (wall_clock_ntp, rtp_timestamp) pair —
 * i.e. "this RTP TS happened at this NTP time". Combined with the track's
 * RTP clock rate, that lets us map any RTP timestamp on that track to NTP
 * nanoseconds.
 *
 * Cross-track sync works because both audio's and video's NTP timestamps
 * come from the same camera wall clock, so audio NTP and video NTP are
 * directly comparable.
 *
 * To translate NTP-time to system time (the only clock the renderer can
 * schedule against), we anchor on the first audio PCM submission:
 * sysTimeNs at that moment corresponds to NTP-time-of-that-rtp. From then
 * on, every other frame's render time is just `origin + ntpForRtp(...)`.
 *
 * If RTCP SR hasn't arrived yet for either track, lookups return null and
 * the renderer falls back to "render immediately" — i.e. current
 * (unsynced) behavior.
 */
class AvSyncClock {

    private data class Anchor(
        val wallNs: Long,
        val rtpTs: Long,
        val clockRateHz: Int,
    )

    @Volatile private var videoAnchor: Anchor? = null
    @Volatile private var audioAnchor: Anchor? = null
    @Volatile private var presentationOriginNs: Long = Long.MIN_VALUE

    /** Latch the video track anchor on RTCP SR. [clockRateHz] is typically 90000. */
    fun onVideoSenderReport(sr: RtcpPacket.SenderReport, clockRateHz: Int) {
        videoAnchor = Anchor(
            wallNs = ntpToNanos(sr.ntpTimestamp),
            rtpTs = sr.rtpTimestamp,
            clockRateHz = clockRateHz,
        )
    }

    /** Latch the audio track anchor on RTCP SR. [clockRateHz] is the audio sample rate. */
    fun onAudioSenderReport(sr: RtcpPacket.SenderReport, clockRateHz: Int) {
        audioAnchor = Anchor(
            wallNs = ntpToNanos(sr.ntpTimestamp),
            rtpTs = sr.rtpTimestamp,
            clockRateHz = clockRateHz,
        )
    }

    /**
     * Anchor the presentation timeline. Call once when audio first hands a
     * PCM block to AudioTrack — at [sysTimeNs] the playback is presenting
     * the audio sample with RTP timestamp [audioRtpTs].
     */
    fun anchorPresentation(audioRtpTs: Long, sysTimeNs: Long) {
        if (presentationOriginNs != Long.MIN_VALUE) return
        val audio = audioAnchor ?: return
        val wallNs = audio.wallNs + (audioRtpTs - audio.rtpTs) * 1_000_000_000L / audio.clockRateHz
        presentationOriginNs = sysTimeNs - wallNs
    }

    /**
     * Returns the system nanoTime() at which a video frame with the given
     * RTP timestamp should be presented, or null if cross-track sync isn't
     * established yet.
     */
    fun systemTimeForVideoRtp(videoRtpTs: Long): Long? {
        val origin = presentationOriginNs
        if (origin == Long.MIN_VALUE) return null
        val video = videoAnchor ?: return null
        val wallNs = video.wallNs + (videoRtpTs - video.rtpTs) * 1_000_000_000L / video.clockRateHz
        return origin + wallNs
    }

    /** True once both SRs and the presentation anchor have landed. */
    fun isReady(): Boolean =
        videoAnchor != null && audioAnchor != null && presentationOriginNs != Long.MIN_VALUE

    /**
     * Convert a 64-bit NTP timestamp (RFC 3550 §4: hi32=seconds since
     * 1900, lo32=fractional seconds) to nanoseconds in that same epoch.
     * We never need the absolute NTP value — only the *differences*
     * between video and audio NTPs and between successive SRs — so the
     * epoch's offset doesn't matter as long as we're consistent.
     */
    private fun ntpToNanos(ntp64: Long): Long {
        val seconds = (ntp64 ushr 32) and 0xFFFFFFFFL
        val fraction = ntp64 and 0xFFFFFFFFL
        val fractionNs = (fraction * 1_000_000_000L) ushr 32
        return seconds * 1_000_000_000L + fractionNs
    }
}
