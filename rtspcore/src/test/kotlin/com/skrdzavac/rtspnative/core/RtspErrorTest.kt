// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.core

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RtspErrorTest {

    @Test
    fun `server errors are transient`() {
        assertTrue(RtspError.ServerError(500, "Internal Server Error").isRetryable)
        assertTrue(RtspError.ServerError(503, "Service Unavailable").isRetryable)
    }

    @Test
    fun `501 and 505 cannot be retried into success`() {
        assertFalse(RtspError.Protocol("501", statusCode = 501).isRetryable)
        assertFalse(RtspError.Protocol("505", statusCode = 505).isRetryable)
    }

    @Test
    fun `other protocol errors keep retrying`() {
        assertTrue(RtspError.Protocol("404", statusCode = 404).isRetryable)
        assertTrue(RtspError.Protocol("malformed").isRetryable)
    }

    @Test
    fun `auth and cancellation are terminal`() {
        assertFalse(RtspError.Auth("bad creds").isRetryable)
        assertFalse(RtspError.Cancelled().isRetryable)
    }

    @Test
    fun `network timeout and codec errors are retryable`() {
        assertTrue(RtspError.Network("eof").isRetryable)
        assertTrue(RtspError.Timeout("stall").isRetryable)
        assertTrue(RtspError.Codec("boom").isRetryable)
    }
}
