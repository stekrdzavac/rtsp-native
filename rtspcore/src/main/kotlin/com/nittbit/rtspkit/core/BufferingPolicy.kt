package com.nittbit.rtspkit.core

sealed class BufferingPolicy(
    val jitterBufferMs: Int,
    val preRollMs: Int,
    val stallTimeoutMs: Int,
) {
    object LowLatency : BufferingPolicy(jitterBufferMs = 50, preRollMs = 0, stallTimeoutMs = 500)
    object Balanced : BufferingPolicy(jitterBufferMs = 200, preRollMs = 150, stallTimeoutMs = 1000)
    object HighLatencyTolerant : BufferingPolicy(jitterBufferMs = 600, preRollMs = 400, stallTimeoutMs = 2500)
}
