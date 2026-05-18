package com.nittbit.rtspkit

import android.util.Log
import android.view.Surface
import com.nittbit.rtspkit.audiorendering.RtspAudioRenderer
import com.nittbit.rtspkit.clocksync.ClockSync
import com.nittbit.rtspkit.core.AudioCodec
import com.nittbit.rtspkit.core.ReconnectPolicy
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import kotlin.random.Random

/**
 * Public entry point for playing an RTSP stream. Supports H.264 / H.265
 * video and AAC / G.711 / L16 audio over TCP-interleaved transport.
 *
 * Includes reconnect-on-failure per [RtspSessionConfiguration.reconnect].
 * Transient drops (camera reboot, WiFi blip, NVR rotating sessions) are
 * detected via:
 *   - stall: no RTP frames in [stallThresholdMs] while in Playing
 *   - keepalive failure: GET_PARAMETER errors after Playing
 *
 * On detection: partial-cleanup (transport + decoders released, the
 * audio renderer is kept so the AudioTrack is reused), wait per backoff,
 * try again. Authentication failures and explicit `stop()` short-circuit
 * retry.
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

    /** Mute / volume of audio output. Survives reconnects. */
    val audioRenderer: RtspAudioRenderer = RtspAudioRenderer()

    private var transport: TcpInterleavedTransport? = null
    private var rtspClient: RtspClient? = null
    private var videoPipeline: VideoPipeline? = null
    private var audioPipeline: AudioPipeline? = null
    private var clockSync: ClockSync? = null

    private val surfaceReady = CompletableDeferred<Surface>()

    @Volatile private var stopRequested = false
    @Volatile private var lastRtpFrameAt: Long = 0
    @Volatile private var pipelineHealth: CompletableDeferred<Unit>? = null
    private var lifecycleJob: Job? = null

    /** A stall is "much longer than the policy's user-facing stall timeout". */
    private val stallThresholdMs: Long
        get() = (config.bufferingPolicy.stallTimeoutMs * 10L).coerceAtLeast(5_000L)

    fun start() {
        if (lifecycleJob?.isActive == true) return
        if (stopRequested) return
        lifecycleJob = scope.launch { runWithReconnect() }
    }

    fun stop() {
        if (stopRequested) return
        stopRequested = true
        val rc = rtspClient
        val health = pipelineHealth
        val lifecycle = lifecycleJob
        scope.launch {
            // Send TEARDOWN while connection is still up.
            runCatching { rc?.teardown() }
            // Trip the current attempt's await (if any).
            health?.completeExceptionally(RtspError.Cancelled())
            // Wait for the reconnect loop to exit cleanly.
            runCatching { lifecycle?.cancelAndJoin() }
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

    private suspend fun runWithReconnect() {
        var attempt = 0
        while (!stopRequested) {
            try {
                runOnePipeline()
                // runOnePipeline only returns normally when health.await()
                // completed without exception, which shouldn't happen —
                // treat as a clean exit.
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: RtspError.Cancelled) {
                return
            } catch (e: RtspError.Auth) {
                Log.i(TAG, "auth failed; not reconnecting")
                fail(e)
                return
            } catch (e: Throwable) {
                if (stopRequested) return
                attempt++
                partialCleanup()
                val backoff = nextBackoff(attempt) ?: run {
                    fail(e)
                    return
                }
                _state.value = RtspSessionState.Reconnecting
                Log.i(TAG, "reconnecting in ${backoff}ms (attempt $attempt): ${e.message}")
                delay(backoff)
            }
        }
    }

    private suspend fun runOnePipeline() = coroutineScope {
        val health = CompletableDeferred<Unit>()
        pipelineHealth = health

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

            // Subscribe to interleaved channels BEFORE PLAY. These collectors
            // are children of this coroutineScope, so they die automatically
            // between reconnect attempts.
            launch(Dispatchers.Default) {
                tx.frames.filter { it.channel == 0 }.collect { handleVideoRtp(it) }
            }
            launch(Dispatchers.Default) {
                tx.frames.filter { it.channel == 1 }.collect { handleRtcp(it) }
            }
            launch(Dispatchers.Default) {
                tx.frames.filter { it.channel == 2 }.collect { handleAudioRtp(it) }
            }
            launch(Dispatchers.Default) {
                tx.frames.filter { it.channel == 3 }.collect { /* audio RTCP ignored Stage 2 */ }
            }

            val handshake = client.handshake(videoOnly = config.videoOnly)
            val videoInfo = handshake.videoTrack.info as TrackInfo.Video
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
                launch {
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

            // Keepalive — escalates failures to the health monitor.
            launch(Dispatchers.IO) {
                while (isActive) {
                    delay(config.keepaliveIntervalMs)
                    val result = runCatching { client.keepalive() }
                    if (result.isFailure && !health.isCompleted) {
                        health.completeExceptionally(
                            RtspError.Network("keepalive failed", result.exceptionOrNull())
                        )
                        return@launch
                    }
                }
            }

            // Stall detector — only fires once Playing was reached and no
            // RTP frame has arrived in [stallThresholdMs].
            launch {
                while (isActive) {
                    delay(1_000)
                    if (_state.value !is RtspSessionState.Playing) {
                        lastRtpFrameAt = System.currentTimeMillis()
                        continue
                    }
                    val elapsed = System.currentTimeMillis() - lastRtpFrameAt
                    if (elapsed > stallThresholdMs && !health.isCompleted) {
                        health.completeExceptionally(
                            RtspError.Timeout("RTP stall: no frames for ${elapsed}ms")
                        )
                        return@launch
                    }
                }
            }

            lastRtpFrameAt = System.currentTimeMillis()
            _state.value = RtspSessionState.Playing
            Log.i(TAG, "session is Playing")

            // Suspend until something completes health exceptionally
            // (stop(), keepalive failure, stall, or any other coroutine
            // child failure propagating up).
            health.await()
        } finally {
            pipelineHealth = null
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
        lastRtpFrameAt = System.currentTimeMillis()
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
        lastRtpFrameAt = System.currentTimeMillis()
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

    /**
     * Backoff for the [attempt]th retry (1-based). Returns null if the
     * policy disallows further retries.
     */
    private fun nextBackoff(attempt: Int): Long? {
        return when (val policy = config.reconnect) {
            is ReconnectPolicy.Never -> null
            is ReconnectPolicy.ExponentialBackoff -> {
                val shift = (attempt - 1).coerceIn(0, 20)
                val base = (policy.initialMs shl shift).coerceAtMost(policy.maxMs)
                val jitter = if (policy.jitterMs > 0) {
                    Random.nextLong(-policy.jitterMs, policy.jitterMs + 1)
                } else 0L
                (base + jitter).coerceAtLeast(0L)
            }
        }
    }

    private fun fail(t: Throwable) {
        val err = (t as? RtspError) ?: RtspError.Network(t.message ?: "unknown error", t)
        cleanup()
        _state.value = RtspSessionState.Failed(err)
    }

    /** Between reconnect attempts — release transport + decoders, keep the renderer. */
    private fun partialCleanup() {
        runCatching { videoPipeline?.release() }
        videoPipeline = null
        runCatching { audioPipeline?.release() }
        audioPipeline = null
        runCatching { transport?.close() }
        transport = null
        rtspClient = null
        clockSync = null
    }

    /** Full cleanup on terminal stop / fail. */
    private fun cleanup() {
        partialCleanup()
        runCatching { audioRenderer.release() }
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
