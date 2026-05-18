package com.nittbit.rtspkit.signaling

object RtspStatus {
    const val OK = 200
    const val MOVED_PERMANENTLY = 301
    const val MOVED_TEMPORARILY = 302
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val METHOD_NOT_ALLOWED = 405
    const val PARAMETER_NOT_UNDERSTOOD = 451
    const val SESSION_NOT_FOUND = 454
    const val UNSUPPORTED_TRANSPORT = 461
    const val INTERNAL_SERVER_ERROR = 500
    const val NOT_IMPLEMENTED = 501
}
