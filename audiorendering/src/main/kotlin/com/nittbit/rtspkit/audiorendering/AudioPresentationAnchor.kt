package com.nittbit.rtspkit.audiorendering

/**
 * Called once on the first PCM write to AudioTrack. The implementation
 * (AvSyncClock in `:rtspkit`) anchors its presentation timeline so that
 * video frames can be scheduled in the same wall-clock domain that the
 * audio is currently playing in.
 *
 * Kept here as a small interface to avoid a `:audiorendering` →
 * `:clocksync` dependency.
 */
fun interface AudioPresentationAnchor {
    fun onFirstPcmSubmitted(audioRtpTs: Long, sysTimeNs: Long)
}
