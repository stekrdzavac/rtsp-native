// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamsLoaderTest {

    @Test
    fun parses_valid_array_with_credentials() {
        val json = """
            [
              {"url":"rtsp://host/a","username":"u","password":"p"},
              {"url":"rtsp://host/b","username":"x","password":"y"}
            ]
        """.trimIndent()

        val result = StreamsLoader.parse(json)

        assertTrue(result is StreamsResult.Ok)
        val entries = (result as StreamsResult.Ok).entries
        assertEquals(2, entries.size)
        assertEquals(StreamEntry("rtsp://host/a", "u", "p"), entries[0])
        assertEquals(StreamEntry("rtsp://host/b", "x", "y"), entries[1])
    }

    @Test
    fun missing_username_and_password_default_to_empty_string() {
        val json = """[{"url":"rtsp://host/a"}]"""

        val result = StreamsLoader.parse(json)

        assertTrue(result is StreamsResult.Ok)
        val entry = (result as StreamsResult.Ok).entries.single()
        assertEquals("rtsp://host/a", entry.url)
        assertEquals("", entry.username)
        assertEquals("", entry.password)
    }

    @Test
    fun null_username_and_password_become_empty_string() {
        val json = """[{"url":"rtsp://host/a","username":null,"password":null}]"""

        val result = StreamsLoader.parse(json)

        assertTrue(result is StreamsResult.Ok)
        val entry = (result as StreamsResult.Ok).entries.single()
        assertEquals("", entry.username)
        assertEquals("", entry.password)
    }

    @Test
    fun top_level_object_is_rejected() {
        val json = """{"url":"rtsp://host/a"}"""

        val result = StreamsLoader.parse(json)

        assertTrue(result is StreamsResult.Error)
    }

    @Test
    fun entry_without_url_is_rejected() {
        val json = """[{"username":"u","password":"p"}]"""

        val result = StreamsLoader.parse(json)

        assertTrue(result is StreamsResult.Error)
        assertTrue((result as StreamsResult.Error).message.contains("url"))
    }

    @Test
    fun entry_with_blank_url_is_rejected() {
        val json = """[{"url":"   "}]"""

        val result = StreamsLoader.parse(json)

        assertTrue(result is StreamsResult.Error)
        assertTrue((result as StreamsResult.Error).message.contains("url"))
    }

    @Test
    fun malformed_json_returns_error() {
        val json = "[ this is not json"

        val result = StreamsLoader.parse(json)

        assertTrue(result is StreamsResult.Error)
    }

    @Test
    fun empty_array_is_ok_with_no_entries() {
        val result = StreamsLoader.parse("[]")

        assertTrue(result is StreamsResult.Ok)
        assertEquals(0, (result as StreamsResult.Ok).entries.size)
    }
}
