package com.skrdzavac.rtspnative.core

/**
 * A decoder-ready access unit. For video this is one full picture (Annex-B
 * NAL bytes); for audio it's one frame. PTS is kept as the raw 32-bit RTP
 * timestamp; ClockSync converts to wall-clock nanos.
 */
sealed class AccessUnit {
    abstract val ptsRtp: Long
    abstract val payload: ByteArray

    data class Video(
        override val ptsRtp: Long,
        override val payload: ByteArray,
        val isKeyframe: Boolean,
    ) : AccessUnit() {
        override fun equals(other: Any?): Boolean =
            other is Video && ptsRtp == other.ptsRtp && isKeyframe == other.isKeyframe && payload.contentEquals(other.payload)
        override fun hashCode(): Int =
            (ptsRtp.hashCode() * 31 + isKeyframe.hashCode()) * 31 + payload.contentHashCode()
    }

    data class Audio(
        override val ptsRtp: Long,
        override val payload: ByteArray,
    ) : AccessUnit() {
        override fun equals(other: Any?): Boolean =
            other is Audio && ptsRtp == other.ptsRtp && payload.contentEquals(other.payload)
        override fun hashCode(): Int =
            ptsRtp.hashCode() * 31 + payload.contentHashCode()
    }
}
