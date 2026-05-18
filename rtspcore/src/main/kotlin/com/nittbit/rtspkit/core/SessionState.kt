package com.nittbit.rtspkit.core

sealed class RtspSessionState {
    object Idle : RtspSessionState()
    object Connecting : RtspSessionState()
    object Authenticating : RtspSessionState()
    object Negotiating : RtspSessionState()
    object Playing : RtspSessionState()
    data class Stalled(val reason: String) : RtspSessionState()
    object Reconnecting : RtspSessionState()
    data class Failed(val error: RtspError) : RtspSessionState()
    object Stopped : RtspSessionState()
}
