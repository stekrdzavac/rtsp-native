// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.signaling

import com.skrdzavac.rtspnative.core.Credentials
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DigestAuthTest {

    /**
     * RFC 2617 §3.5 canonical example.
     *   HA1 = MD5("Mufasa:testrealm@host.com:Circle Of Life")
     *       = 939e7578ed9e3c518a452acee763bce9
     *   HA2 = MD5("GET:/dir/index.html")
     *       = 39aff3a2bab6126f332b942af96d3366
     *   response = MD5(HA1:nonce:nc:cnonce:qop:HA2)
     *            = 6629fae49393a05397450978507c4ef1
     */
    @Test
    fun `produces RFC 2617 reference response for qop auth`() {
        val challenge = AuthChallenge.Digest(
            realm = "testrealm@host.com",
            nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093",
            qop = "auth",
            opaque = "5ccc069c403ebaf9f0171e9517f40e41",
            algorithm = null,
        )
        val header = DigestAuth.header(
            credentials = Credentials("Mufasa", "Circle Of Life"),
            challenge = challenge,
            method = "GET",
            uri = "/dir/index.html",
            nc = 1,
            cnonce = "0a4f113b",
        )
        assertTrue(header.startsWith("Digest "))
        val params = WwwAuthenticate.parse(header) as? AuthChallenge.Digest
            ?: error("re-parse should accept our own output as a digest header")
        // We can't introspect 'response' via AuthChallenge.Digest, so do a string contains check.
        assertTrue(header.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(header.contains("nc=00000001"))
        assertTrue(header.contains("cnonce=\"0a4f113b\""))
        assertTrue(header.contains("qop=auth"))
        assertEquals("dcd98b7102dd2f0e8b11d0f600bfb0c093", params.nonce)
    }

    @Test
    fun `produces response without qop when challenge omits it`() {
        val challenge = AuthChallenge.Digest(
            realm = "ipcam",
            nonce = "abc123",
            qop = null,
            opaque = null,
            algorithm = null,
        )
        val header = DigestAuth.header(
            credentials = Credentials("admin", "password"),
            challenge = challenge,
            method = "DESCRIBE",
            uri = "rtsp://camera/stream",
        )
        assertTrue(header.contains("Digest "))
        assertTrue(!header.contains("qop="))
        assertTrue(!header.contains("nc="))
        assertTrue(!header.contains("cnonce="))
    }
}
