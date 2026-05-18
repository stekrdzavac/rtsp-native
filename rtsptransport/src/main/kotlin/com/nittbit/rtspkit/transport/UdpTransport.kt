package com.nittbit.rtspkit.transport

import com.nittbit.rtspkit.signaling.RtspRequest
import com.nittbit.rtspkit.signaling.RtspResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RTSP control over TCP, RTP and RTCP over separate UDP sockets per
 * media track. Channel ids surface synthetically as 0/1/2/3 to match the
 * TCP-interleaved transport's shape — so the session-level demux code
 * doesn't care which transport is in play.
 *
 * Stage 2 limitations:
 *   - No jitter buffer; packets are emitted in arrival order. Reordering
 *     happens at the depacketizer only.
 *   - No RTCP RR (Receiver Reports) sent back. Most cameras don't require
 *     them on LAN, but some NAT/firewall setups will.
 *   - No "NAT punching"; relies on the camera sending to the client port
 *     we advertised in SETUP. Works on LAN; may not work through NATs.
 *
 * UDP sockets are bound at construction time so that the
 * `client_port=X-Y` pair can be passed to SETUP.
 */
class UdpTransport private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    val videoSockets: UdpRtpSocketPair,
    val audioSockets: UdpRtpSocketPair?,
    scope: CoroutineScope,
) : RtspTransport {

    private val _frames = MutableSharedFlow<InterleavedFrame>(
        replay = 0,
        extraBufferCapacity = 512,
    )
    override val frames: SharedFlow<InterleavedFrame> = _frames.asSharedFlow()

    private val responses = Channel<RtspResponse>(capacity = Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)

    private val readerJob: Job = scope.launch(Dispatchers.IO) { readRtspLoop() }
    private val videoRtpJob: Job = scope.launch(Dispatchers.IO) {
        readUdpLoop(videoSockets.rtpSocket, channel = 0)
    }
    private val videoRtcpJob: Job = scope.launch(Dispatchers.IO) {
        readUdpLoop(videoSockets.rtcpSocket, channel = 1)
    }
    private val audioRtpJob: Job? = audioSockets?.let { pair ->
        scope.launch(Dispatchers.IO) { readUdpLoop(pair.rtpSocket, channel = 2) }
    }
    private val audioRtcpJob: Job? = audioSockets?.let { pair ->
        scope.launch(Dispatchers.IO) { readUdpLoop(pair.rtcpSocket, channel = 3) }
    }

    override suspend fun send(request: RtspRequest) {
        withContext(Dispatchers.IO) {
            output.write(request.toBytes())
            output.flush()
        }
    }

    override suspend fun receive(): RtspResponse = responses.receive()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        readerJob.cancel()
        videoRtpJob.cancel()
        videoRtcpJob.cancel()
        audioRtpJob?.cancel()
        audioRtcpJob?.cancel()
        runCatching { socket.close() }
        videoSockets.close()
        audioSockets?.close()
        responses.close()
    }

    private suspend fun readRtspLoop() {
        try {
            while (!socket.isClosed) {
                val event = InterleavedFraming.readOne(input) ?: break
                when (event) {
                    is InterleavedFraming.Event.Message -> responses.send(event.response)
                    is InterleavedFraming.Event.Frame -> {
                        // Unexpected: RTSP/TCP shouldn't carry interleaved data
                        // when the transport is UDP. Silently drop.
                    }
                }
            }
        } catch (_: Throwable) {
            // Socket closed or peer disconnect.
        } finally {
            responses.close()
        }
    }

    private suspend fun readUdpLoop(udp: DatagramSocket, channel: Int) {
        // 64KB max UDP packet size, larger than any RTP packet we'll see.
        val buf = ByteArray(64 * 1024)
        val packet = DatagramPacket(buf, buf.size)
        try {
            while (!udp.isClosed) {
                packet.length = buf.size
                udp.receive(packet)
                val payload = ByteArray(packet.length)
                System.arraycopy(packet.data, packet.offset, payload, 0, packet.length)
                _frames.emit(InterleavedFrame(channel, payload))
            }
        } catch (_: Throwable) {
            // Socket closed.
        }
    }

    companion object {
        /**
         * Open a TCP control connection and bind UDP socket pairs for
         * video (always) and audio (if [withAudio]).
         */
        suspend fun connect(
            host: String,
            port: Int,
            connectTimeoutMs: Int,
            withAudio: Boolean,
            scope: CoroutineScope,
        ): UdpTransport = withContext(Dispatchers.IO) {
            val tcp = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), connectTimeoutMs)
            }
            val videoSockets = UdpRtpSocketPair.bind()
            val audioSockets = if (withAudio) UdpRtpSocketPair.bind() else null
            UdpTransport(
                socket = tcp,
                input = BufferedInputStream(tcp.getInputStream(), 64 * 1024),
                output = tcp.getOutputStream(),
                videoSockets = videoSockets,
                audioSockets = audioSockets,
                scope = scope,
            )
        }
    }
}
