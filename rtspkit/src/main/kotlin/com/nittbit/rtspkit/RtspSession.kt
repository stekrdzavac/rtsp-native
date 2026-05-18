package com.nittbit.rtspkit

import android.util.Log
import android.view.Surface
import com.nittbit.rtspkit.audiorendering.RtspAudioRenderer
import com.nittbit.rtspkit.clocksync.ClockSync
import com.nittbit.rtspkit.core.AudioCodec
import com.nittbit.rtspkit.core.RtcpPacket
import com.nittbit.rtspkit.core.RtpPacket
import com.nittbit.rtspkit.core.RtspError
import com.nittbit.rtspkit.core.RtspSessionState
import com.nittbit.rtspkit.core.SessionStatistics
import com.nittbit.rtspkit.core.VideoCodec
import com.nittbit.rtspkit.signaling.NegotiatedTrack
import com.nittbit.rtspkit.signaling.RtspClient
import com.nittbit.rtspkit.signaling.TrackInfo
import com.nittbit.rtspkit.transport.InterleavedFrame
import com.nittbit.rtspkit.transport.TcpInterleavedTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

/**
 * Public entry point for playing an RTSP stream. Supports H.264 / H.265
 * video and AAC / G.711 / L16 audio over TCP-interleaved transport.
 * Construct → [start] → attach a Surface (via
 * [com.nittbit.rtspkit.videorendering.RtspVideoView]) → observe [state]
 * and [statistics] → [stop].
 */
class RtspSession(private val config: RtspSessionConfiguration) {

    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "scope exception", t)
        fail(t)
    }

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Default + exceptionHandler)

    private val _state = MutableStateFlow<RtspSessionState>(RtspSessionState.Idle)
    val state: StateFlow<RtspSessionState> = _state

    private val _statistics = MutableStateFlow(SessionStatistics())
    val statistics: StateFlow<SessionStatistics> = _statistics

    private val _videoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoSize: StateFlow<Pair<Int, Int>?> = _videoSize

    /** Mute / volume / lifecycle of audio output. */
    val audioRenderer: RtspAudioRenderer = RtspAudioRenderer()

    private var transport: TcpInterleavedTransport? = null
    private var rtspClient: RtspClient? = null
    private var videoPipeline: VideoPipeline? = null
    private var audioPipeline: AudioPipeline? = null
    private var clockSync: ClockSync? = null

    private val surfaceReady = CompletableDeferred<Surface>()

    fun start() {
        if (_state.value !is RtspSessionState.Idle &&
            _state.value !is RtspSessionState.Stopped &&
            _state.value !is RtspSessionState.Failed
        ) return
        scope.launch { runPipeline() }
    }

    fun stop() {
        scope.launch {
            runCatching { rtspClient?.teardown() }
            cleanup()
            _state.value = RtspSessionState.Stopped
        }
    }

    fun attachSurface(surface: Surface) {
        Log.i(TAG, "attachSurface($surface)")
        if (!surfaceReady.isCompleted) {
            surfaceReady.complete(surface)
        } else {
            videoPipeline?.replaceSurface(surface)
        }
    }

    fun detachSurface() {
        Log.i(TAG, "detachSurface")
    }

    private suspend fun runPipeline() {
        try {
            _state.value = RtspSessionState.Connecting
            val (host, port) = parseHostPort(config.url)
            Log.i(TAG, "connecting to $host:$port")

            val tx = TcpInterleavedTransport.connect(host, port, config.connectTimeoutMs, scope)
            transport = tx

            _state.value = RtspSessionState.Authenticating
            val client = RtspClient(tx, config.url, config.credentials)
            rtspClient = client

            _state.value = RtspSessionState.Negotiating
            val videoRtpChannel = 0
            val videoRtcpChannel = 1
            val audioRtpChannel = 2
            val audioRtcpChannel = 3

            // Subscribe to all four interleaved channels BEFORE PLAY so
            // frames aren't dropped during the SharedFlow's no-subscriber
            // window.
            scope.launch(Dispatchers.Default) {
                tx.frames.filter { it.channel == videoRtpChannel }.collect { handleVideoRtp(it) }
            }
            scope.launch(Dispatchers.Default) {
                tx.frames.filter { it.channel == videoRtcpChannel }.collect { handleRtcp(it) }
            }
            scope.launch(Dispatchers.Default) {
                tx.frames.filter { it.channel == audioRtpChannel }.collect { handleAudioRtp(it) }
            }
            scope.launch(Dispatchers.Default) {
                tx.frames.filter { it.channel == audioRtcpChannel }.collect {
                    /* audio RTCP — Stage 2 ignores; clock is anchored to the video track */
                }
            }

            val handshake = client.handshake(videoOnly = config.videoOnly)
            val videoTrack = handshake.videoTrack
            val videoInfo = videoTrack.info as TrackInfo.Video
            val audioInfo = (handshake.audioTrack?.info as? TrackInfo.Audio)
            Log.i(TAG, "handshake complete: video=${videoInfo.codec}, audio=${audioInfo?.codec}")

            videoPipeline = buildVideoPipeline(videoInfo)
            audioPipeline = handshake.audioTrack?.let { buildAudioPipeline(it) }
            clockSync = ClockSync(videoInfo.clockRate)

            Log.i(TAG, "waiting for Surface (timeout=${config.firstFrameTimeoutMs}ms)")
            val surface = withTimeoutOrNull(config.firstFrameTimeoutMs.toLong()) {
                surfaceReady.await()
            } ?: throw RtspError.Timeout("no Surface attached within ${config.firstFrameTimeoutMs}ms")

            videoPipeline?.let { vp ->
                if (vp.canStart()) {
                    Log.i(TAG, "starting ${videoInfo.codec} decoder with SDP-derived params")
                    vp.start(surface)
                }
                scope.launch {
                    vp.dimensions.filterNotNull().collect { (w, h) ->
                        _videoSize.value = w to h
                        _statistics.value = _statistics.value.copy(videoWidth = w, videoHeight = h)
                    }
                }
            }

            audioPipeline?.let { ap ->
                if (ap.canStart()) {
                    Log.i(TAG, "starting audio decoder (${audioInfo?.codec})")
                    ap.start()
                }
            }

            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(config.keepaliveIntervalMs)
                    runCatching { client.keepalive() }
                }
            }

            _state.value = RtspSessionState.Playing
            Log.i(TAG, "session is Playing")
        } catch (e: Throwable) {
            Log.e(TAG, "pipeline failed", e)
            fail(e)
        }
    }

    private fun buildVideoPipeline(track: TrackInfo.Video): VideoPipeline {
        val pl: VideoPipeline = when (track.codec) {
            VideoCodec.H264 -> H264Pipeline(scope)
            VideoCodec.H265 -> H265Pipeline(scope)
        }
        pl.seedFromSdp(track)
        return pl
    }

    private fun buildAudioPipeline(negotiated: NegotiatedTrack): AudioPipeline? {
        val info = negotiated.info as? TrackInfo.Audio ?: return null
        return when (info.codec) {
            AudioCodec.AAC -> AacPipeline(scope, info, audioRenderer)
            AudioCodec.PCMU, AudioCodec.PCMA -> G711Pipeline(info, audioRenderer)
            AudioCodec.L16 -> L16Pipeline(info, audioRenderer)
        }
    }

    private fun handleVideoRtp(frame: InterleavedFrame) {
        val packet = RtpPacket.parse(frame.payload) ?: return
        val pl = videoPipeline ?: return
        val aus = pl.depacketize(packet)
        if (aus.isEmpty()) return

        if (!pl.isStarted() && pl.canStart()) {
            val surface = if (surfaceReady.isCompleted) {
                runCatching { surfaceReady.getCompleted() }.getOrNull()
            } else null
            if (surface != null) {
                runCatching { pl.start(surface) }
            }
        }

        for (au in aus) {
            _statistics.value = _statistics.value.copy(
                framesDecoded = _statistics.value.framesDecoded + 1,
            )
            pl.feed(au)
        }
    }

    private fun handleAudioRtp(frame: InterleavedFrame) {
        val packet = RtpPacket.parse(frame.payload) ?: return
        val pl = audioPipeline ?: return
        val aus = pl.depacketize(packet)
        if (aus.isEmpty()) return
        if (!pl.isStarted() && pl.canStart()) pl.start()
        for (au in aus) pl.feed(au)
    }

    private fun handleRtcp(frame: InterleavedFrame) {
        val packets = RtcpPacket.parseAll(frame.payload)
        val sync = clockSync ?: return
        for (p in packets) {
            if (p is RtcpPacket.SenderReport) sync.onSenderReport(p)
        }
    }

    private fun fail(t: Throwable) {
        val err = (t as? RtspError) ?: RtspError.Network(t.message ?: "unknown error", t)
        cleanup()
        _state.value = RtspSessionState.Failed(err)
    }

    private fun cleanup() {
        runCatching { videoPipeline?.release() }
        videoPipeline = null
        runCatching { audioPipeline?.release() }
        audioPipeline = null
        runCatching { audioRenderer.release() }
        runCatching { transport?.close() }
        transport = null
        rtspClient = null
        clockSync = null
    }

    private fun parseHostPort(url: String): Pair<String, Int> {
        val uri = URI(url)
        val host = uri.host ?: throw RtspError.Network("invalid rtsp url: missing host")
        val port = if (uri.port > 0) uri.port else 554
        return host to port
    }

    companion object {
        private const val TAG = "RtspSession"
    }
}
