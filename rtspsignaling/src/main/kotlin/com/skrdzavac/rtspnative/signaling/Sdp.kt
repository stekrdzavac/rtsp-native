// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.signaling

import com.skrdzavac.rtspnative.core.AudioCodec
import com.skrdzavac.rtspnative.core.VideoCodec

/**
 * Minimal SDP parser. Recognizes the subset RTSPKit cares about:
 *   m=<media> <port> RTP/AVP <pt>
 *   a=rtpmap:<pt> <encoding>/<clockrate>[/<channels>]
 *   a=fmtp:<pt> <key>=<value>;<key>=<value>
 *   a=control:<url>
 *
 * Returns one [TrackInfo] per media line whose encoding the library can
 * actually play. Unknown encodings are skipped silently.
 */
object Sdp {

    fun parse(text: String): List<TrackInfo> {
        val lines = text.lineSequence().map { it.trimEnd('\r') }.toList()
        val tracks = mutableListOf<TrackInfo>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("m=")) {
                // m=<media> <port> <proto> <pt> ...
                val parts = line.removePrefix("m=").split(' ')
                if (parts.size < 4) { i++; continue }
                val media = parts[0]
                val payloadType = parts[3].toIntOrNull() ?: run { i++; continue }

                var rtpmapEncoding: String? = null
                var clockRate = 0
                var channels = 1
                var control: String? = null
                val fmtp = mutableMapOf<String, String>()

                i++
                while (i < lines.size && !lines[i].startsWith("m=")) {
                    val attr = lines[i]
                    when {
                        attr.startsWith("a=rtpmap:") -> {
                            val rest = attr.removePrefix("a=rtpmap:")
                            val space = rest.indexOf(' ')
                            if (space > 0 && rest.substring(0, space).toIntOrNull() == payloadType) {
                                val map = rest.substring(space + 1).split('/')
                                rtpmapEncoding = map[0]
                                clockRate = map.getOrNull(1)?.toIntOrNull() ?: 0
                                channels = map.getOrNull(2)?.toIntOrNull() ?: 1
                            }
                        }
                        attr.startsWith("a=fmtp:") -> {
                            val rest = attr.removePrefix("a=fmtp:")
                            val space = rest.indexOf(' ')
                            if (space > 0 && rest.substring(0, space).toIntOrNull() == payloadType) {
                                rest.substring(space + 1).split(';').forEach { kv ->
                                    val eq = kv.indexOf('=')
                                    if (eq > 0) {
                                        fmtp[kv.substring(0, eq).trim()] = kv.substring(eq + 1).trim()
                                    }
                                }
                            }
                        }
                        attr.startsWith("a=control:") -> {
                            control = attr.removePrefix("a=control:").trim()
                        }
                    }
                    i++
                }

                val controlUrl = control ?: ""
                when (media) {
                    "video" -> buildVideoTrack(payloadType, rtpmapEncoding, clockRate, controlUrl, fmtp)
                        ?.let { tracks += it }
                    "audio" -> buildAudioTrack(payloadType, rtpmapEncoding, clockRate, channels, controlUrl, fmtp)
                        ?.let { tracks += it }
                }
            } else {
                i++
            }
        }
        return tracks
    }

    private fun buildVideoTrack(
        pt: Int,
        encoding: String?,
        clockRate: Int,
        control: String,
        fmtp: Map<String, String>,
    ): TrackInfo.Video? {
        val codec = when (encoding?.uppercase()) {
            "H264" -> VideoCodec.H264
            "H265", "HEVC" -> VideoCodec.H265
            else -> return null
        }
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        when (codec) {
            VideoCodec.H264 -> {
                val sprop = fmtp["sprop-parameter-sets"]
                if (sprop != null) {
                    val parts = sprop.split(',')
                    sps = parts.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { Base64Decoder.decode(it) }
                    pps = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { Base64Decoder.decode(it) }
                }
            }
            VideoCodec.H265 -> {
                // RFC 7798 §7.1: separate sprop fields per parameter set type.
                vps = fmtp["sprop-vps"]?.takeIf { it.isNotEmpty() }?.let { Base64Decoder.decode(it) }
                sps = fmtp["sprop-sps"]?.takeIf { it.isNotEmpty() }?.let { Base64Decoder.decode(it) }
                pps = fmtp["sprop-pps"]?.takeIf { it.isNotEmpty() }?.let { Base64Decoder.decode(it) }
            }
        }
        val mode = fmtp["packetization-mode"]?.toIntOrNull() ?: 1
        return TrackInfo.Video(
            payloadType = pt,
            codec = codec,
            clockRate = if (clockRate > 0) clockRate else 90_000,
            controlUrl = control,
            vps = vps,
            sps = sps,
            pps = pps,
            packetizationMode = mode,
        )
    }

    private fun buildAudioTrack(
        pt: Int,
        encoding: String?,
        clockRate: Int,
        channels: Int,
        control: String,
        fmtp: Map<String, String>,
    ): TrackInfo.Audio? {
        // For static payload types 0 (PCMU) and 8 (PCMA), the encoding may be omitted in some camera SDPs.
        val codec = when (encoding?.uppercase()) {
            "MPEG4-GENERIC", "AAC" -> AudioCodec.AAC
            "PCMU" -> AudioCodec.PCMU
            "PCMA" -> AudioCodec.PCMA
            "L16" -> AudioCodec.L16
            null -> when (pt) {
                0 -> AudioCodec.PCMU
                8 -> AudioCodec.PCMA
                else -> return null
            }
            else -> return null
        }
        val configBytes = fmtp["config"]?.let { HexDecoder.decode(it) }
        val resolvedClock = if (clockRate > 0) clockRate else when (codec) {
            AudioCodec.PCMU, AudioCodec.PCMA -> 8_000
            AudioCodec.L16, AudioCodec.AAC -> 44_100
        }
        val sizeLength = fmtp["sizeLength"]?.toIntOrNull() ?: 13
        val indexLength = fmtp["indexLength"]?.toIntOrNull() ?: 3
        val indexDeltaLength = fmtp["indexDeltaLength"]?.toIntOrNull() ?: indexLength
        return TrackInfo.Audio(
            payloadType = pt,
            codec = codec,
            clockRate = resolvedClock,
            controlUrl = control,
            channels = channels,
            configBytes = configBytes,
            sizeLength = sizeLength,
            indexLength = indexLength,
            indexDeltaLength = indexDeltaLength,
        )
    }
}
