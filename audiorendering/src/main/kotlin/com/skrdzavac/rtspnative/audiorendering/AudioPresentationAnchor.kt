// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.audiorendering

/**
 * Called on each non-muted PCM write to AudioTrack until anchoring
 * succeeds. The implementation (AvSyncClock in `:rtsp`) anchors its
 * presentation timeline so that video frames can be scheduled in the
 * same wall-clock domain that the audio is currently playing in.
 *
 * Returns true when anchoring has succeeded (or was already done), in
 * which case the caller stops invoking this method. Returns false when
 * the implementation still needs more input (e.g. the audio RTCP SR
 * hasn't arrived yet) — the caller should retry on the next PCM block.
 *
 * Kept here as a small interface to avoid a `:audiorendering` →
 * `:clocksync` dependency.
 */
fun interface AudioPresentationAnchor {
    fun onFirstPcmSubmitted(audioRtpTs: Long, sysTimeNs: Long): Boolean
}
