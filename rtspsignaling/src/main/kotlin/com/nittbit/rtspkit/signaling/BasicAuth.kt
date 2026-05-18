package com.nittbit.rtspkit.signaling

import com.nittbit.rtspkit.core.Credentials

object BasicAuth {
    fun header(credentials: Credentials): String {
        val raw = "${credentials.username}:${credentials.password}".toByteArray(Charsets.UTF_8)
        return "Basic ${Base64Encoder.encode(raw)}"
    }
}
