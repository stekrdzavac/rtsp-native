// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.videodecoder

/**
 * Tells the video decoder when to render a frame. Implemented by
 * `AvSyncClock` in `:rtsp`; kept here as a small interface to avoid
 * `:videodecoder` depending on `:clocksync`.
 */
fun interface VideoRenderClock {
    /**
     * Returns the system nanoTime() at which a frame with the given RTP
     * timestamp should be rendered, or null if sync hasn't been
     * established yet (the decoder will fall back to rendering
     * immediately).
     */
    fun systemTimeNsForVideoRtp(videoRtpTs: Long): Long?
}
