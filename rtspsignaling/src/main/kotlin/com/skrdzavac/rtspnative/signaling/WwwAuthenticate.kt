// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.signaling

/**
 * Parsed WWW-Authenticate challenge. Supports the two schemes RTSPKit
 * speaks: Basic and Digest. Digest fields follow RFC 7616 nomenclature.
 */
sealed class AuthChallenge {
    abstract val realm: String?

    data class Basic(override val realm: String?) : AuthChallenge()

    data class Digest(
        override val realm: String?,
        val nonce: String,
        val qop: String?,
        val opaque: String?,
        val algorithm: String?,
    ) : AuthChallenge()
}

object WwwAuthenticate {

    /**
     * Parse one or more comma-separated WWW-Authenticate challenge values.
     * Returns the first one our implementation supports (Digest preferred).
     */
    fun parse(headerValue: String): AuthChallenge? {
        val trimmed = headerValue.trim()
        return when {
            trimmed.startsWith("Digest", ignoreCase = true) -> parseDigest(trimmed.substring(6))
            trimmed.startsWith("Basic", ignoreCase = true) -> parseBasic(trimmed.substring(5))
            else -> null
        }
    }

    private fun parseBasic(rest: String): AuthChallenge.Basic {
        val params = parseParams(rest)
        return AuthChallenge.Basic(realm = params["realm"])
    }

    private fun parseDigest(rest: String): AuthChallenge.Digest? {
        val params = parseParams(rest)
        val nonce = params["nonce"] ?: return null
        return AuthChallenge.Digest(
            realm = params["realm"],
            nonce = nonce,
            qop = params["qop"],
            opaque = params["opaque"],
            algorithm = params["algorithm"],
        )
    }

    /**
     * Split a comma-separated `key=value` parameter list. Values may be
     * double-quoted; quoted commas are preserved.
     */
    private fun parseParams(input: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        val n = input.length
        while (i < n) {
            // skip leading whitespace and commas
            while (i < n && (input[i] == ' ' || input[i] == ',' || input[i] == '\t')) i++
            if (i >= n) break
            val keyStart = i
            while (i < n && input[i] != '=') i++
            if (i >= n) break
            val key = input.substring(keyStart, i).trim().lowercase()
            i++ // skip '='
            // value: quoted or token
            val value: String
            if (i < n && input[i] == '"') {
                i++
                val valStart = i
                while (i < n && input[i] != '"') {
                    if (input[i] == '\\' && i + 1 < n) i++ // skip escape
                    i++
                }
                value = input.substring(valStart, i)
                if (i < n) i++ // skip closing quote
            } else {
                val valStart = i
                while (i < n && input[i] != ',') i++
                value = input.substring(valStart, i).trim()
            }
            out[key] = value
        }
        return out
    }
}
