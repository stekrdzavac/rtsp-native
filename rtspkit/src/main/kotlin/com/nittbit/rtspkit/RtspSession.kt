package com.nittbit.rtspkit

import android.util.Log
import android.view.Surface
import com.nittbit.rtspkit.clocksync.ClockSync
import com.nittbit.rtspkit.core.RtcpPacket
import com.nittbit.rtspkit.core.RtpPacket
import com.nittbit.rtspkit.core.RtspError
import com.nittbit.rtspkit.core.RtspSessionState
import com.nittbit.rtspkit.core.SessionStatistics
import com.nittbit.rtspkit.core.VideoCodec
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
 * Public entry point for playing an RTSP/H.264 or RTSP/H.265 stream.
 * Stage 1 + Stage 2 (H.265) support a single video track over
 * TCP-interleaved transport. Construct → [start] → attach a Surface (via
 * [com.nittbit.rtspkit.videorendering.RtspVideoView]) → observe [state]
 * and [statistics] → [stop].
 *
 * The session owns its own coroutine scope. Calling [stop] (or any
 * unrecoverable error path) cancels it, closes the socket, and releases
 * the decoder.
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

    private var transport: TcpInterleavedTransport? = null
    private var rtspClient: RtspClient? = null
    private var pipeline: VideoPipeline? = null
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
            pipeline?.replaceSurface(surface)
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
            val rtpChannel = 0
            val rtcpChannel = 1

            // Pipeline is allocated lazily once we know the codec from SDP.
            // Subscribe to RTP/RTCP NOW so frames after PLAY aren't lost.
            scope.launch(Dispatchers.Default) {
                tx.frames
                    .filter { it.channel == rtpChannel }
                    .collect { frame -> handleRtp(frame) }
            }
            scope.launch(Dispatchers.Default) {
                tx.frames
                    .filter { it.channel == rtcpChannel }
                    .collect { frame -> handleRtcp(frame) }
            }

            val handshake = client.handshake(videoOnly = true)
            val track = handshake.videoTrack
            val videoTrack = track.info as TrackInfo.Video
            Log.i(TAG, "handshake complete: codec=${videoTrack.codec}, channels rtp=${track.rtpChannel}/rtcp=${track.rtcpChannel}")

            val pl: VideoPipeline = when (videoTrack.codec) {
                VideoCodec.H264 -> H264Pipeline(scope)
                VideoCodec.H265 -> H265Pipeline(scope)
            }
            pl.seedFromSdp(videoTrack)
            pipeline = pl

            clockSync = ClockSync(videoTrack.clockRate)

            Log.i(TAG, "waiting for Surface (timeout=${config.firstFrameTimeoutMs}ms)")
            val surface = withTimeoutOrNull(config.firstFrameTimeoutMs.toLong()) {
                surfaceReady.await()
            } ?: throw RtspError.Timeout("no Surface attached within ${config.firstFrameTimeoutMs}ms")

            if (pl.canStart()) {
                Log.i(TAG, "starting ${videoTrack.codec} decoder with SDP-derived params")
                pl.start(surface)
            }

            scope.launch {
                pl.dimensions.filterNotNull().collect { (w, h) ->
                    _videoSize.value = w to h
                    _statistics.value = _statistics.value.copy(
                        videoWidth = w,
                        videoHeight = h,
                    )
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

    private fun handleRtp(frame: InterleavedFrame) {
        val packet = RtpPacket.parse(frame.payload) ?: return
        val pl = pipeline ?: return
        val aus = pl.depacketize(packet)
        if (aus.isEmpty()) return

        if (!pl.isStarted() && pl.canStart()) {
            // Lazy start when in-band parameter sets arrive (cameras whose
            // SDP omits sprop-* parameter sets).
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
        runCatching { pipeline?.release() }
        pipeline = null
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
