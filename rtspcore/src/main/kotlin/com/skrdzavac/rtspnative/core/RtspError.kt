// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.core

sealed class RtspError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class Network(message: String, cause: Throwable? = null) : RtspError(message, cause)

    /**
     * The server objected to our request or we could not make sense of
     * its reply. [statusCode] is set when the error was derived from an
     * RTSP status line.
     */
    class Protocol(message: String, cause: Throwable? = null, val statusCode: Int? = null) : RtspError(message, cause)

    /**
     * A 5xx reply: the server reporting its own failure (RFC 2326 §7.1.1),
     * not objecting to the request. NVRs answer a burst of simultaneous
     * DESCRIBEs this way when momentarily overloaded, so it is treated as
     * transient and fed to the reconnect scheduler like a network drop.
     */
    class ServerError(val statusCode: Int, val reason: String) :
        RtspError("RTSP server error $statusCode $reason")

    class Auth(message: String) : RtspError(message)
    class Codec(message: String, cause: Throwable? = null) : RtspError(message, cause)
    class Timeout(message: String) : RtspError(message)
    class Cancelled(message: String = "session cancelled") : RtspError(message)

    /**
     * Whether a reconnect attempt could plausibly change the outcome.
     * Bad credentials cannot be retried into success, and neither can
     * 501 Not Implemented / 505 RTSP Version Not Supported.
     */
    val isRetryable: Boolean
        get() = when (this) {
            is Auth, is Cancelled -> false
            is Protocol -> statusCode != 501 && statusCode != 505
            else -> true
        }
}
