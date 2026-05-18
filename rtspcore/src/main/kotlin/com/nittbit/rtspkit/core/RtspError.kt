package com.nittbit.rtspkit.core

sealed class RtspError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class Network(message: String, cause: Throwable? = null) : RtspError(message, cause)
    class Protocol(message: String, cause: Throwable? = null) : RtspError(message, cause)
    class Auth(message: String) : RtspError(message)
    class Codec(message: String, cause: Throwable? = null) : RtspError(message, cause)
    class Timeout(message: String) : RtspError(message)
    class Cancelled(message: String = "session cancelled") : RtspError(message)
}
