// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative

import com.skrdzavac.rtspnative.core.AudioCodec
import com.skrdzavac.rtspnative.core.BufferingPolicy
import com.skrdzavac.rtspnative.core.Credentials
import com.skrdzavac.rtspnative.core.ReconnectPolicy
import com.skrdzavac.rtspnative.core.TransportPreference
import com.skrdzavac.rtspnative.core.VideoCodec

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
    val transport: TransportPreference = TransportPreference.Auto,
    val videoOnly: Boolean = false,
    val connectTimeoutMs: Int = 8_000,
    val firstFrameTimeoutMs: Int = 10_000,
    val keepaliveIntervalMs: Long = 30_000,
    val reconnect: ReconnectPolicy = ReconnectPolicy.ExponentialBackoff(
        initialMs = 500,
        maxMs = 30_000,
        jitterMs = 250,
    ),
    val preferredVideoCodec: List<VideoCodec> = listOf(VideoCodec.H264, VideoCodec.H265),
    val preferredAudioCodec: List<AudioCodec> = listOf(AudioCodec.AAC, AudioCodec.PCMU, AudioCodec.PCMA),
    val bufferingPolicy: BufferingPolicy = BufferingPolicy.LowLatency,
)
