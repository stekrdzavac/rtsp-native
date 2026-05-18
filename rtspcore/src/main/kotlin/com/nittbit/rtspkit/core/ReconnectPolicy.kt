package com.nittbit.rtspkit.core

sealed class ReconnectPolicy {
    object Never : ReconnectPolicy()

    data class ExponentialBackoff(
        val initialMs: Long = 500,
        val maxMs: Long = 30_000,
        val jitterMs: Long = 250,
    ) : ReconnectPolicy()
}
