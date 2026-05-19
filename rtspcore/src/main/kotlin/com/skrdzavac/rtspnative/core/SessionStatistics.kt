// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.core

data class SessionStatistics(
    val bitrateBps: Long = 0,
    val fps: Float = 0f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val framesDecoded: Long = 0,
    val framesDropped: Long = 0,
)
