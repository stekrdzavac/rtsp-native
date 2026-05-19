// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.transport

import com.skrdzavac.rtspnative.core.RtspError
import com.skrdzavac.rtspnative.signaling.RtspResponse
import java.io.InputStream

/**
 * Pure-function helpers for the RFC 2326 §10.12 RTSP/RTP interleaved
 * framing. Each TCP read either starts with `$` (an interleaved RTP/RTCP
 * frame) or an ASCII line that begins an RTSP response.
 */
object InterleavedFraming {
    const val MAGIC: Int = 0x24

    /**
     * Read one event from [input]. Returns null if EOF is reached cleanly
     * (before any byte of a new event is read).
     */
    fun readOne(input: InputStream): Event? {
        val first = input.read()
        if (first < 0) return null
        return if (first == MAGIC) readInterleaved(input) else readRtspMessage(input, first)
    }

    private fun readInterleaved(input: InputStream): Event.Frame {
        val channel = readByteStrict(input)
        val hi = readByteStrict(input)
        val lo = readByteStrict(input)
        val length = (hi shl 8) or lo
        val payload = ByteArray(length)
        readFully(input, payload, 0, length)
        return Event.Frame(channel, payload)
    }

    private fun readRtspMessage(input: InputStream, firstByte: Int): Event.Message {
        // Accumulate bytes until CRLFCRLF, then read Content-Length body bytes if any.
        val sink = ArrayList<Byte>(1024)
        sink += firstByte.toByte()

        // crlfState tracks how much of the "\r\n\r\n" sequence we've matched.
        var crlfState = 0
        while (true) {
            val b = readByteStrict(input)
            sink += b.toByte()
            crlfState = when {
                crlfState == 0 && b == 0x0D -> 1
                crlfState == 1 && b == 0x0A -> 2
                crlfState == 2 && b == 0x0D -> 3
                crlfState == 3 && b == 0x0A -> 4
                else -> 0
            }
            if (crlfState == 4) break
        }

        val headerBytes = sink.toByteArray()
        val contentLength = extractContentLength(headerBytes)
        val full = if (contentLength == 0) {
            headerBytes
        } else {
            val combined = ByteArray(headerBytes.size + contentLength)
            System.arraycopy(headerBytes, 0, combined, 0, headerBytes.size)
            readFully(input, combined, headerBytes.size, contentLength)
            combined
        }
        val response = RtspResponse.parse(full)
            ?: throw RtspError.Protocol("malformed RTSP response")
        return Event.Message(response)
    }

    private fun extractContentLength(headerBytes: ByteArray): Int {
        val text = String(headerBytes, Charsets.ISO_8859_1)
        // case-insensitive line scan
        for (line in text.split("\r\n")) {
            val sep = line.indexOf(':')
            if (sep <= 0) continue
            if (line.substring(0, sep).trim().equals("Content-Length", ignoreCase = true)) {
                return line.substring(sep + 1).trim().toIntOrNull() ?: 0
            }
        }
        return 0
    }

    private fun readByteStrict(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw RtspError.Network("unexpected end of stream")
        return b
    }

    private fun readFully(input: InputStream, dest: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val n = input.read(dest, offset + read, length - read)
            if (n < 0) throw RtspError.Network("unexpected end of stream")
            read += n
        }
    }

    sealed class Event {
        data class Frame(val channel: Int, val payload: ByteArray) : Event()
        data class Message(val response: RtspResponse) : Event()
    }

    private fun ArrayList<Byte>.toByteArray(): ByteArray {
        val out = ByteArray(size)
        for (i in indices) out[i] = this[i]
        return out
    }
}
