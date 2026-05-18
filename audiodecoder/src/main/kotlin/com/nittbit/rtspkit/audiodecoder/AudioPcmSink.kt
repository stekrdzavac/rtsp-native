package com.nittbit.rtspkit.audiodecoder

/**
 * Callback the decoders use to ship decoded PCM into the audio renderer.
 * Decoders don't know about [AudioTrack]; they just hand off 16-bit PCM
 * frames.
 */
fun interface AudioPcmSink {
    /**
     * @param pcm    decoded PCM samples, native byte order, signed 16-bit
     * @param sampleRateHz   sample rate of the data
     * @param channels  1 = mono, 2 = stereo (interleaved L,R,L,R…)
     * @param rtpTs   the RTP timestamp of the originating audio frame
     *   (used by the renderer to anchor A/V sync)
     */
    fun onPcm(pcm: ShortArray, sampleRateHz: Int, channels: Int, rtpTs: Long)
}
