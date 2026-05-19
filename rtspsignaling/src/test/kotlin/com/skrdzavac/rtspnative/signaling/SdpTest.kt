// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.signaling

import com.skrdzavac.rtspnative.core.AudioCodec
import com.skrdzavac.rtspnative.core.VideoCodec
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SdpTest {

    @Test
    fun `parses a typical H264 + PCMA SDP`() {
        val sdp = """
            v=0
            o=- 1 1 IN IP4 192.168.1.10
            s=Media Presentation
            t=0 0
            a=control:*
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1;profile-level-id=42c01e;sprop-parameter-sets=Z0KAH9pAUBboQAAAAwBAAAAPI4AAtxsAA,aM48gA==
            a=control:trackID=1
            m=audio 0 RTP/AVP 8
            a=rtpmap:8 PCMA/8000
            a=control:trackID=2
        """.trimIndent()

        val tracks = Sdp.parse(sdp)
        assertEquals(2, tracks.size)

        val video = tracks[0] as TrackInfo.Video
        assertEquals(VideoCodec.H264, video.codec)
        assertEquals(96, video.payloadType)
        assertEquals(90_000, video.clockRate)
        assertEquals(1, video.packetizationMode)
        assertEquals("trackID=1", video.controlUrl)
        assertNotNull(video.sps)
        assertNotNull(video.pps)

        val audio = tracks[1] as TrackInfo.Audio
        assertEquals(AudioCodec.PCMA, audio.codec)
        assertEquals(8, audio.payloadType)
        assertEquals(8_000, audio.clockRate)
        assertEquals("trackID=2", audio.controlUrl)
    }

    @Test
    fun `skips unknown video codecs`() {
        val sdp = """
            v=0
            m=video 0 RTP/AVP 26
            a=rtpmap:26 JPEG/90000
            a=control:trackID=1
        """.trimIndent()
        val tracks = Sdp.parse(sdp)
        assertTrue(tracks.isEmpty(), "MJPEG should be skipped in Stage 1")
    }

    @Test
    fun `infers G711 codec from static payload type without rtpmap`() {
        val sdp = """
            v=0
            m=audio 0 RTP/AVP 0
            a=control:trackID=1
        """.trimIndent()
        val tracks = Sdp.parse(sdp)
        assertEquals(1, tracks.size)
        val audio = tracks[0] as TrackInfo.Audio
        assertEquals(AudioCodec.PCMU, audio.codec)
        assertEquals(8_000, audio.clockRate)
    }
}
