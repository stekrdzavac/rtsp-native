// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative

import android.graphics.Bitmap
import android.util.Log
import android.view.Surface
import com.skrdzavac.rtspnative.audiorendering.RtspAudioRenderer
import com.skrdzavac.rtspnative.clocksync.AvSyncClock
import com.skrdzavac.rtspnative.core.AudioCodec
import com.skrdzavac.rtspnative.core.ReconnectPolicy
import com.skrdzavac.rtspnative.core.RtcpPacket
import com.skrdzavac.rtspnative.core.RtpPacket
import com.skrdzavac.rtspnative.core.RtspError
import com.skrdzavac.rtspnative.core.RtspSessionState
import com.skrdzavac.rtspnative.core.SessionStatistics
import com.skrdzavac.rtspnative.core.TransportPreference
import com.skrdzavac.rtspnative.core.VideoCodec
import com.skrdzavac.rtspnative.signaling.NegotiatedTrack
import com.skrdzavac.rtspnative.signaling.RtspClient
import com.skrdzavac.rtspnative.signaling.TrackInfo
import java.io.File
import com.skrdzavac.rtspnative.transport.InterleavedFrame
import com.skrdzavac.rtspnative.transport.RtspTransport
import com.skrdzavac.rtspnative.transport.TcpInterleavedTransport
import com.skrdzavac.rtspnative.transport.UdpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
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

    /** A/V sync clock. Survives reconnects; anchored on first audio PCM. */
    private val avSync: AvSyncClock = AvSyncClock()

    /** Mute / volume of audio output. Survives reconnects. */
    val audioRenderer: RtspAudioRenderer = RtspAudioRenderer(
        presentationAnchor = avSync::anchorPresentation,
    )

    private var transport: RtspTransport? = null
    private var rtspClient: RtspClient? = null
    private var videoPipeline: VideoPipeline? = null
    private var audioPipeline: AudioPipeline? = null
    private var videoClockRate: Int = 90_000
    private var audioClockRate: Int = 0
    private var currentVideoTrack: TrackInfo.Video? = null
    private var currentAudioTrack: TrackInfo.Audio? = null

    @Volatile private var recorder: RtspRecorder? = null

    private val surfaceReady = CompletableDeferred<Surface>()
    @Volatile private var currentSurface: Surface? = null

    @Volatile private var stopRequested = false
    @Volatile private var explicitlyPaused = false
    @Volatile private var surfaceAttached = false
    @Volatile private var lastRtpFrameAt: Long = 0
    @Volatile private var pipelineHealth: CompletableDeferred<Unit>? = null
    private var lifecycleJob: Job? = null

    private val bytesReceived = AtomicLong(0)

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
        val wasDetached = !surfaceAttached
        Log.i(TAG, "attachSurface($surface) wasDetached=$wasDetached")
        currentSurface = surface
        surfaceAttached = true
        if (!surfaceReady.isCompleted) {
            surfaceReady.complete(surface)
            return
        }
        // SurfaceView's surfaceChanged callback also lands here on every
        // layout pass, even when the same surface stays valid. Only treat
        // this as a "resume after loss" if we actually went through a
        // detachSurface() — otherwise replaceSurface() alone is enough,
        // and triggering resumeVideo() would needlessly tear down the
        // running decoder and force a wait for the next keyframe.
        videoPipeline?.replaceSurface(surface)
        if (wasDetached && shouldRenderVideo()) {
            Log.i(TAG, "resuming video after surface detach")
            videoPipeline?.resumeVideo()
        }
    }

    fun detachSurface(surface: Surface? = null) {
        // Ignore stale detaches. During a grid<->fullscreen handoff a disposing
        // view can fire detachSurface() AFTER the session has already been
        // rebound to a newer view's surface; honoring it would null the live
        // surface and pause the pipeline, freezing the tile. Only honor a
        // detach for the surface that is actually current (or a null/legacy
        // force-detach from internal teardown).
        if (surface != null && currentSurface !== surface) {
            Log.i(TAG, "detachSurface ignored (stale; not current)")
            return
        }
        Log.i(TAG, "detachSurface")
        surfaceAttached = false
        currentSurface = null
        videoPipeline?.pauseVideo()
    }

    /**
     * Suspend rendering of both video and audio while keeping the RTSP
     * session, transport, and decoders configured. Use this to free up
     * the rendering surface and audio output without paying the cost
     * of reconnecting later. Idempotent.
     *
     * RTSP keepalive continues. Network bytes keep arriving — they
     * just don't reach the screen / speaker until [resume].
     */
    fun pause() {
        if (explicitlyPaused) return
        explicitlyPaused = true
        videoPipeline?.pauseVideo()
        audioPipeline?.pauseAudio()
        audioRenderer.pause()
    }

    /**
     * Resume after [pause]. The video pipeline will drop AUs until the
     * next IDR keyframe so the first rendered frame is self-contained;
     * depending on the camera's GOP, expect up to a couple of seconds
     * before video reappears. If no surface is currently attached,
     * video stays paused until one is attached. Idempotent.
     */
    fun resume() {
        if (!explicitlyPaused) return
        explicitlyPaused = false
        audioRenderer.resume()
        audioPipeline?.resumeAudio()
        if (shouldRenderVideo()) videoPipeline?.resumeVideo()
    }

    private fun shouldRenderVideo(): Boolean = surfaceAttached && !explicitlyPaused

    @Volatile private var snapshotter: (suspend () -> Bitmap?)? = null

    /**
     * Registered by [com.skrdzavac.rtspnative.videorendering.RtspVideoView] when
     * it attaches to this session. Cleared on detach. Null is a valid value
     * (audio-only sessions, or sessions with no view yet).
     */
    fun setSnapshotter(snapshotter: (suspend () -> Bitmap?)?) {
        this.snapshotter = snapshotter
    }

    /**
     * Capture the currently displayed video frame to a Bitmap at the
     * decoded video resolution. Returns null if no view is attached, the
     * surface isn't ready, or the platform copy fails.
     */
    suspend fun snapshot(): Bitmap? = snapshotter?.invoke()

    /**
     * Start recording the current stream to [file] as MP4. Returns the
     * [RtspRecorder] handle so the caller can observe `state` and
     * `bytesWritten`, or null if the session isn't in `Playing` state or
     * the negotiated tracks are missing the parameter sets needed for
     * the MP4 codec config (`sprop-*` fmtp values from SDP).
     *
     * Only one recording per session at a time. Calling again replaces
     * the previous (and stops it).
     */
    suspend fun startRecording(file: File): RtspRecorder? {
        if (_state.value !is RtspSessionState.Playing) return null
        val video = currentVideoTrack ?: return null
        val audio = currentAudioTrack

        // Need width/height. Wait briefly if the decoder hasn't reported
        // OUTPUT_FORMAT_CHANGED yet — usually arrives within ~200ms of
        // Playing.
        val size = _videoSize.value
            ?: withTimeoutOrNull(2_000) { _videoSize.filterNotNull().first() }
            ?: return null

        // Parameter sets come from the depacketizer, NOT from the SDP
        // TrackInfo: many cameras (notably Hikvision HEVC) ship them
        // in-band via RTP instead of through sprop-vps/sps/pps fmtp.
        // The depacketizer has them by Playing time.
        val params = videoPipeline?.parameterSets() ?: run {
            Log.w(TAG, "no video parameter sets yet; cannot start recording")
            return null
        }

        recorder?.stop()
        val rec = try {
            RtspRecorder(
                file = file,
                videoCodec = video.codec,
                videoClockRate = video.clockRate,
                videoVps = params.vps,
                videoSps = params.sps,
                videoPps = params.pps,
                audioTrackInfo = audio,
                width = size.first,
                height = size.second,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "could not construct recorder", t)
            return null
        }
        rec.start()
        recorder = rec
        return rec
    }

    /** Finalize any in-flight recording. Safe to call when not recording. */
    fun stopRecording() {
        recorder?.stop()
        recorder = null
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
            // Auto currently maps to TCP-interleaved — the safe default
            // that works through NAT. A future Stage 3 may make Auto try
            // UDP first with TCP fallback.
            val useUdp = when (config.transport) {
                is TransportPreference.Udp -> true
                is TransportPreference.TcpInterleaved -> false
                is TransportPreference.Auto -> false
            }
            Log.i(TAG, "connecting to $host:$port (${if (useUdp) "UDP" else "TCP-interleaved"})")

            val tx: RtspTransport = if (useUdp) {
                UdpTransport.connect(
                    host = host,
                    port = port,
                    connectTimeoutMs = config.connectTimeoutMs,
                    withAudio = !config.videoOnly,
                    scope = scope,
                )
            } else {
                TcpInterleavedTransport.connect(host, port, config.connectTimeoutMs, scope)
            }
            transport = tx

            _state.value = RtspSessionState.Authenticating
            val client = RtspClient(tx, config.url, config.credentials)
            rtspClient = client

            _state.value = RtspSessionState.Negotiating

            // Subscribe to interleaved channels BEFORE PLAY. These collectors
            // are children of this coroutineScope, so they die automatically
            // between reconnect attempts. Channel ids are the same shape on
            // both transports (synthetic 0..3 for UDP).
            launch(Dispatchers.Default) {
                tx.frames.filter { it.channel == 0 }.collect { handleVideoRtp(it) }
            }
            launch(Dispatchers.Default) {
                tx.frames.filter { it.channel == 1 }.collect { handleVideoRtcp(it) }
            }
            launch(Dispatchers.Default) {
                tx.frames.filter { it.channel == 2 }.collect { handleAudioRtp(it) }
            }
            launch(Dispatchers.Default) {
                tx.frames.filter { it.channel == 3 }.collect { handleAudioRtcp(it) }
            }

            val transportBuilder = if (useUdp) buildUdpTransportBuilder(tx as UdpTransport)
                                   else RtspClient.TransportBuilder.TCP_INTERLEAVED
            val handshake = client.handshake(
                videoOnly = config.videoOnly,
                transportBuilder = transportBuilder,
            )
            val videoInfo = handshake.videoTrack.info as TrackInfo.Video
            val audioInfo = (handshake.audioTrack?.info as? TrackInfo.Audio)
            Log.i(TAG, "handshake complete: video=${videoInfo.codec}, audio=${audioInfo?.codec}")

            videoPipeline = buildVideoPipeline(videoInfo)
            audioPipeline = handshake.audioTrack?.let { buildAudioPipeline(it) }
            videoClockRate = videoInfo.clockRate
            audioClockRate = audioInfo?.clockRate ?: 0
            currentVideoTrack = videoInfo
            currentAudioTrack = audioInfo

            Log.i(TAG, "waiting for Surface (timeout=${config.firstFrameTimeoutMs}ms)")
            withTimeoutOrNull(config.firstFrameTimeoutMs.toLong()) {
                surfaceReady.await()
            } ?: throw RtspError.Timeout("no Surface attached within ${config.firstFrameTimeoutMs}ms")

            videoPipeline?.let { vp ->
                // Use the latest known surface — the consumer may have
                // already swapped it (e.g., Activity rotation) between
                // surfaceReady completing and us getting here.
                val surface = currentSurface
                if (surface != null && vp.canStart() && shouldRenderVideo()) {
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
                    if (explicitlyPaused) ap.pauseAudio()
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

            // 1Hz statistics sampler — computes fps from Δframes and
            // bitrate from Δbytes over the last second.
            launch {
                var lastFrames = 0L
                var lastBytes = 0L
                bytesReceived.set(0)
                while (isActive) {
                    delay(1_000)
                    if (_state.value !is RtspSessionState.Playing) {
                        lastFrames = _statistics.value.framesDecoded
                        lastBytes = bytesReceived.get()
                        continue
                    }
                    val nowFrames = _statistics.value.framesDecoded
                    val nowBytes = bytesReceived.get()
                    val fps = (nowFrames - lastFrames).toFloat()
                    val bitrateBps = (nowBytes - lastBytes) * 8L
                    lastFrames = nowFrames
                    lastBytes = nowBytes
                    val drops = (videoPipeline?.framesDropped ?: 0L) +
                        (audioPipeline?.framesDropped ?: 0L)
                    _statistics.value = _statistics.value.copy(
                        fps = fps,
                        bitrateBps = bitrateBps,
                        framesDropped = drops,
                    )
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

    /**
     * Returns a [RtspClient.TransportBuilder] that emits a UDP-style
     * `client_port=X-Y` per track, reading the ports from the bound UDP
     * sockets.
     */
    private fun buildUdpTransportBuilder(udp: UdpTransport): RtspClient.TransportBuilder =
        RtspClient.TransportBuilder { rtpChannel, _ ->
            val pair = when (rtpChannel) {
                0 -> udp.videoSockets
                2 -> udp.audioSockets
                    ?: error("audio SETUP requested but no audio UDP socket bound")
                else -> error("unexpected RTP channel $rtpChannel for UDP transport")
            }
            "RTP/AVP;unicast;client_port=${pair.rtpPort}-${pair.rtcpPort}"
        }

    private fun buildVideoPipeline(track: TrackInfo.Video): VideoPipeline {
        val jitterMs = config.bufferingPolicy.jitterBufferMs
        val pl: VideoPipeline = when (track.codec) {
            VideoCodec.H264 -> H264Pipeline(scope, avSync::systemTimeForVideoRtp, jitterMs)
            VideoCodec.H265 -> H265Pipeline(scope, avSync::systemTimeForVideoRtp, jitterMs)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun handleVideoRtp(frame: InterleavedFrame) {
        lastRtpFrameAt = System.currentTimeMillis()
        bytesReceived.addAndGet(frame.payload.size.toLong())
        val packet = RtpPacket.parse(frame.payload) ?: return
        val pl = videoPipeline ?: return
        val aus = pl.depacketize(packet)
        if (aus.isEmpty()) return

        if (!pl.isStarted() && pl.canStart() && shouldRenderVideo()) {
            val surface = currentSurface
            if (surface != null) {
                runCatching { pl.start(surface) }
            }
        }

        for (au in aus) {
            _statistics.value = _statistics.value.copy(
                framesDecoded = _statistics.value.framesDecoded + 1,
            )
            recorder?.onVideoAu(au)
            pl.feed(au)
        }
    }

    private fun handleAudioRtp(frame: InterleavedFrame) {
        lastRtpFrameAt = System.currentTimeMillis()
        bytesReceived.addAndGet(frame.payload.size.toLong())
        val packet = RtpPacket.parse(frame.payload) ?: return
        val pl = audioPipeline ?: return
        val aus = pl.depacketize(packet)
        if (aus.isEmpty()) return
        if (!pl.isStarted() && pl.canStart()) pl.start()
        for (au in aus) {
            recorder?.onAudioAu(au)
            pl.feed(au)
        }
    }

    private fun handleVideoRtcp(frame: InterleavedFrame) {
        for (p in RtcpPacket.parseAll(frame.payload)) {
            if (p is RtcpPacket.SenderReport) {
                avSync.onVideoSenderReport(p, videoClockRate)
            }
        }
    }

    private fun handleAudioRtcp(frame: InterleavedFrame) {
        if (audioClockRate <= 0) return
        for (p in RtcpPacket.parseAll(frame.payload)) {
            if (p is RtcpPacket.SenderReport) {
                avSync.onAudioSenderReport(p, audioClockRate)
            }
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
    }

    /** Full cleanup on terminal stop / fail. */
    private fun cleanup() {
        runCatching { recorder?.stop() }
        recorder = null
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
