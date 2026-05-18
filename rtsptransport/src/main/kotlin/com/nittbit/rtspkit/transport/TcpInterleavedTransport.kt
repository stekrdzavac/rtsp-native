package com.nittbit.rtspkit.transport

import com.nittbit.rtspkit.core.RtspError
import com.nittbit.rtspkit.signaling.RtspMessageChannel
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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RTSP control + RTP/RTCP carried over a single TCP socket per
 * RFC 2326 §10.12. The reader coroutine demuxes the stream into:
 *   - [RtspResponse] messages → exposed via [receive]
 *   - [InterleavedFrame] frames → exposed via [frames]
 *
 * The transport does not interpret RTP payloads; that's the
 * depacketizers' job.
 */
class TcpInterleavedTransport private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    scope: CoroutineScope,
) : RtspMessageChannel {

    private val _frames = MutableSharedFlow<InterleavedFrame>(
        replay = 0,
        extraBufferCapacity = 512,
    )
    val frames: SharedFlow<InterleavedFrame> = _frames.asSharedFlow()

    private val responses = Channel<RtspResponse>(capacity = Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    private val readerJob: Job = scope.launch(Dispatchers.IO) { readLoop() }

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
        runCatching { socket.close() }
        responses.close()
    }

    private suspend fun readLoop() {
        try {
            while (!socket.isClosed) {
                val event = InterleavedFraming.readOne(input) ?: break
                when (event) {
                    is InterleavedFraming.Event.Frame ->
                        _frames.emit(InterleavedFrame(event.channel, event.payload))
                    is InterleavedFraming.Event.Message ->
                        responses.send(event.response)
                }
            }
        } catch (_: Throwable) {
            // Reader exits silently when the socket is closed or peer disconnects.
        } finally {
            responses.close()
        }
    }

    companion object {
        suspend fun connect(
            host: String,
            port: Int,
            connectTimeoutMs: Int,
            scope: CoroutineScope,
        ): TcpInterleavedTransport = withContext(Dispatchers.IO) {
            val socket = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), connectTimeoutMs)
            }
            TcpInterleavedTransport(
                socket = socket,
                input = BufferedInputStream(socket.getInputStream(), 64 * 1024),
                output = socket.getOutputStream(),
                scope = scope,
            )
        }
    }
}
