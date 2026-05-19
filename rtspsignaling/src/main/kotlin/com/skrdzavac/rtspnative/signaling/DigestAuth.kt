// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.signaling

import com.skrdzavac.rtspnative.core.Credentials
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * MD5 / MD5-sess Digest authentication per RFC 7616 (legacy RFC 2617
 * compatible). Stage 1 supports qop=auth.
 */
object DigestAuth {

    fun header(
        credentials: Credentials,
        challenge: AuthChallenge.Digest,
        method: String,
        uri: String,
        nc: Int = 1,
        cnonce: String = randomCnonce(),
    ): String {
        val realm = challenge.realm ?: ""
        val algorithm = challenge.algorithm?.lowercase()
        val qopValue = chooseQop(challenge.qop)

        val a1 = "${credentials.username}:$realm:${credentials.password}"
        var ha1 = md5Hex(a1)
        if (algorithm == "md5-sess") {
            ha1 = md5Hex("$ha1:${challenge.nonce}:$cnonce")
        }
        val a2 = "$method:$uri"
        val ha2 = md5Hex(a2)

        val response = if (qopValue != null) {
            md5Hex("$ha1:${challenge.nonce}:${nc.toHexNc()}:$cnonce:$qopValue:$ha2")
        } else {
            md5Hex("$ha1:${challenge.nonce}:$ha2")
        }

        val sb = StringBuilder("Digest ")
        sb.appendField("username", credentials.username)
        sb.append(", ")
        sb.appendField("realm", realm)
        sb.append(", ")
        sb.appendField("nonce", challenge.nonce)
        sb.append(", ")
        sb.appendField("uri", uri)
        if (challenge.algorithm != null) {
            sb.append(", algorithm=").append(challenge.algorithm)
        }
        sb.append(", ")
        sb.appendField("response", response)
        challenge.opaque?.let {
            sb.append(", ")
            sb.appendField("opaque", it)
        }
        if (qopValue != null) {
            sb.append(", qop=").append(qopValue)
            sb.append(", nc=").append(nc.toHexNc())
            sb.append(", ")
            sb.appendField("cnonce", cnonce)
        }
        return sb.toString()
    }

    private fun StringBuilder.appendField(key: String, value: String) {
        append(key).append("=\"").append(value).append('"')
    }

    private fun chooseQop(qopHeader: String?): String? {
        if (qopHeader.isNullOrBlank()) return null
        val tokens = qopHeader.split(',').map { it.trim().lowercase() }
        return if ("auth" in tokens) "auth" else null
    }

    private fun Int.toHexNc(): String = "%08x".format(this)

    private fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.ISO_8859_1))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX[(b.toInt() ushr 4) and 0xF])
            sb.append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
    private val RANDOM = SecureRandom()

    private fun randomCnonce(): String {
        val bytes = ByteArray(8)
        RANDOM.nextBytes(bytes)
        val sb = StringBuilder(16)
        for (b in bytes) {
            sb.append(HEX[(b.toInt() ushr 4) and 0xF])
            sb.append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }
}
