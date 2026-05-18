package com.skrdzavac.rtspnative.signaling

import com.skrdzavac.rtspnative.core.Credentials

object BasicAuth {
    fun header(credentials: Credentials): String {
        val raw = "${credentials.username}:${credentials.password}".toByteArray(Charsets.UTF_8)
        return "Basic ${Base64Encoder.encode(raw)}"
    }
}
