package com.nittbit.rtspkit

import com.nittbit.rtspkit.core.AudioCodec
import com.nittbit.rtspkit.core.BufferingPolicy
import com.nittbit.rtspkit.core.Credentials
import com.nittbit.rtspkit.core.ReconnectPolicy
import com.nittbit.rtspkit.core.TransportPreference
import com.nittbit.rtspkit.core.VideoCodec

/**
 * Inputs to [RtspSession]. Stage 1 reads:
 *   - [url], [credentials], [connectTimeoutMs], [firstFrameTimeoutMs],
 *     [keepaliveIntervalMs]
 *
 * Stage 2 will additionally honor: [transport] (UDP), [reconnect],
 * [bufferingPolicy], [preferredAudioCodec], `videoOnly = false`.
 */
data class RtspSessionConfiguration(
    val url: String,
    val credentials: Credentials? = null,
    val transport: TransportPreference = TransportPreference.TcpInterleaved,
    val videoOnly: Boolean = true,
    val connectTimeoutMs: Int = 8_000,
    val firstFrameTimeoutMs: Int = 10_000,
    val keepaliveIntervalMs: Long = 30_000,
    val reconnect: ReconnectPolicy = ReconnectPolicy.Never,
    val preferredVideoCodec: List<VideoCodec> = listOf(VideoCodec.H264),
    val preferredAudioCodec: List<AudioCodec> = listOf(AudioCodec.AAC, AudioCodec.PCMU, AudioCodec.PCMA),
    val bufferingPolicy: BufferingPolicy = BufferingPolicy.LowLatency,
)
