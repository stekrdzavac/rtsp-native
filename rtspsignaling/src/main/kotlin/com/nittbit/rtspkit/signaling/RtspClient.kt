package com.nittbit.rtspkit.signaling

import com.nittbit.rtspkit.core.Credentials
import com.nittbit.rtspkit.core.RtspError

/**
 * Drives the RTSP/1.0 control sequence: OPTIONS → DESCRIBE → SETUP per
 * track → PLAY. Pure protocol logic — socket I/O happens via the injected
 * [RtspMessageChannel].
 */
class RtspClient(
    private val channel: RtspMessageChannel,
    private val baseUrl: String,
    private val credentials: Credentials?,
) {
    private var cseq = 0
    private var nextRequestAuth: String? = null
    private var lastChallenge: AuthChallenge? = null
    private var sessionId: String? = null

    suspend fun handshake(
        videoOnly: Boolean = true,
        transportBuilder: TransportBuilder = TransportBuilder.TCP_INTERLEAVED,
    ): HandshakeResult {
        options()
        val tracks = describe()
        if (tracks.isEmpty()) throw RtspError.Protocol("no playable tracks in SDP")

        val video = tracks.firstOrNull { it is TrackInfo.Video }
            ?: throw RtspError.Protocol("no video track in SDP")
        val negotiatedVideo = setup(
            track = video,
            rtpChannel = 0,
            rtcpChannel = 1,
            transportHeader = transportBuilder.build(rtpChannel = 0, rtcpChannel = 1),
        )

        val negotiatedAudio = if (!videoOnly) {
            tracks.firstOrNull { it is TrackInfo.Audio }?.let { audio ->
                runCatching {
                    setup(
                        track = audio,
                        rtpChannel = 2,
                        rtcpChannel = 3,
                        transportHeader = transportBuilder.build(rtpChannel = 2, rtcpChannel = 3),
                    )
                }.getOrNull()
            }
        } else null

        play(negotiatedVideo.sessionId)
        return HandshakeResult(videoTrack = negotiatedVideo, audioTrack = negotiatedAudio)
    }

    suspend fun keepalive() {
        val sid = sessionId ?: return
        sendWithAuth { auth -> RtspRequest.getParameter(baseUrl, ++cseq, sid, auth) }
    }

    suspend fun teardown() {
        val sid = sessionId ?: return
        runCatching {
            sendWithAuth { auth -> RtspRequest.teardown(baseUrl, ++cseq, sid, auth) }
        }
        channel.close()
    }

    private suspend fun options() {
        sendWithAuth { auth -> RtspRequest.options(baseUrl, ++cseq, auth = auth) }
    }

    private suspend fun describe(): List<TrackInfo> {
        val response = sendWithAuth { auth -> RtspRequest.describe(baseUrl, ++cseq, auth = auth) }
        val sdp = String(response.body, Charsets.UTF_8)
        return Sdp.parse(sdp)
    }

    private suspend fun setup(
        track: TrackInfo,
        rtpChannel: Int,
        rtcpChannel: Int,
        transportHeader: String,
    ): NegotiatedTrack {
        val controlUrl = absoluteControlUrl(track.controlUrl)
        val response = sendWithAuth { auth ->
            RtspRequest.setup(controlUrl, ++cseq, transportHeader, sessionId, auth)
        }
        sessionId = response.sessionId ?: sessionId
            ?: throw RtspError.Protocol("SETUP response missing Session header")
        val negotiatedTransport = response.header("Transport") ?: ""
        // For TCP-interleaved the server confirms (or remaps) the channel
        // ids; for UDP we keep the synthetic 0/1/2/3 ids and don't need
        // to parse the server_port pair (we listen on the client_port we
        // advertised, which the camera echoed back).
        val (rtp, rtcp) = parseInterleaved(negotiatedTransport) ?: (rtpChannel to rtcpChannel)
        return NegotiatedTrack(
            info = track,
            rtpChannel = rtp,
            rtcpChannel = rtcp,
            sessionId = sessionId!!,
        )
    }

    private suspend fun play(sid: String) {
        sendWithAuth { auth -> RtspRequest.play(baseUrl, ++cseq, sid, auth) }
    }

    /**
     * Send a request with the current auth header. If the server responds
     * 401, parse the challenge, retry once with credentials.
     */
    private suspend fun sendWithAuth(buildRequest: (auth: String?) -> RtspRequest): RtspResponse {
        var request = buildRequest(nextRequestAuth)
        channel.send(request)
        var response = channel.receive()

        if (response.statusCode == 401) {
            val challengeHeader = response.header("WWW-Authenticate")
                ?: throw RtspError.Auth("401 without WWW-Authenticate")
            val challenge = WwwAuthenticate.parse(challengeHeader)
                ?: throw RtspError.Auth("unsupported auth scheme: $challengeHeader")
            val creds = credentials ?: throw RtspError.Auth("server requires auth but no credentials provided")
            lastChallenge = challenge
            nextRequestAuth = authHeaderFor(challenge, request.method, request.uri, creds)
            request = buildRequest(nextRequestAuth)
            channel.send(request)
            response = channel.receive()
        } else if (lastChallenge is AuthChallenge.Digest) {
            // Refresh Authorization with current method/uri for subsequent requests too.
            credentials?.let {
                nextRequestAuth = authHeaderFor(lastChallenge!!, request.method, request.uri, it)
            }
        }

        if (response.statusCode !in 200..299) {
            throw RtspError.Protocol("RTSP ${request.method} -> ${response.statusCode} ${response.statusMessage}")
        }
        response.sessionId?.let { sessionId = it }
        return response
    }

    private fun authHeaderFor(
        challenge: AuthChallenge,
        method: String,
        uri: String,
        creds: Credentials,
    ): String = when (challenge) {
        is AuthChallenge.Basic -> BasicAuth.header(creds)
        is AuthChallenge.Digest -> DigestAuth.header(creds, challenge, method, uri)
    }

    private fun absoluteControlUrl(control: String): String = when {
        control.isEmpty() || control == "*" -> baseUrl
        control.startsWith("rtsp://", ignoreCase = true) -> control
        else -> if (baseUrl.endsWith('/')) "$baseUrl$control" else "$baseUrl/$control"
    }

    internal fun parseInterleaved(transport: String): Pair<Int, Int>? {
        val token = transport.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("interleaved=", ignoreCase = true) } ?: return null
        val pair = token.removePrefix("interleaved=").trim().split('-')
        val rtp = pair.getOrNull(0)?.toIntOrNull() ?: return null
        val rtcp = pair.getOrNull(1)?.toIntOrNull() ?: (rtp + 1)
        return rtp to rtcp
    }

    data class HandshakeResult(
        val videoTrack: NegotiatedTrack,
        val audioTrack: NegotiatedTrack? = null,
    )

    /**
     * Strategy for the SETUP `Transport:` header. The TCP-interleaved
     * default is built in; UDP usage is delegated to a custom builder
     * supplied by the transport (which knows the local UDP ports it
     * bound).
     */
    fun interface TransportBuilder {
        fun build(rtpChannel: Int, rtcpChannel: Int): String

        companion object {
            val TCP_INTERLEAVED = TransportBuilder { rtp, rtcp ->
                "RTP/AVP/TCP;unicast;interleaved=$rtp-$rtcp"
            }
        }
    }
}
