// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.core

/**
 * One-shot notifications from a session, as opposed to the sticky
 * [RtspSessionState].
 */
sealed class RtspSessionEvent {
    /**
     * Packet loss broke the decoder's reference chain and output is
     * suppressed until the next IDR. Emitted once per loss episode, not
     * once per lost packet. The library has no in-band way to shorten the
     * wait (RTCP PLI/FIR is not implemented and cameras rarely honour it);
     * a host that can ask the camera for a sync point out of band (for
     * example ONVIF SetSynchronizationPoint) should do so on this event.
     */
    object KeyframeNeeded : RtspSessionEvent()
}
