// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.videodecoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/**
 * One decoder candidate considered for a given resolution. Plain data so the
 * selection logic ([chooseDecoder]) can be unit tested without the Android
 * media framework.
 */
internal data class DecoderOption(
    val name: String,
    val hardware: Boolean,
    val supportsSize: Boolean,
)

/**
 * Pick a decoder for the target resolution.
 *
 * If any decoder advertises support for the size, prefer hardware among them
 * (or software when [preferSoftware], e.g. after a hardware decoder faulted).
 *
 * If NOTHING advertises the size, fall back to a SOFTWARE decoder anyway:
 * hardware decoders hard-fault past their advertised level (the device's only
 * HW HEVC decoder rejects 2K outright), whereas the AOSP software decoder
 * routinely decodes beyond its conservatively-advertised level. Trying software
 * is strictly better than re-trying the hardware decoder we know will fault.
 * Returns null only when there are no decoders for the mime at all.
 */
internal fun chooseDecoder(options: List<DecoderOption>, preferSoftware: Boolean): String? {
    if (options.isEmpty()) return null
    val capable = options.filter { it.supportsSize }
    if (capable.isNotEmpty()) {
        return if (preferSoftware) {
            (capable.firstOrNull { !it.hardware } ?: capable.first()).name
        } else {
            (capable.firstOrNull { it.hardware } ?: capable.first()).name
        }
    }
    return (options.firstOrNull { !it.hardware } ?: options.first()).name
}

/**
 * Creates [MediaCodec] decoders, choosing one that can actually handle the
 * stream's resolution.
 *
 * Why this exists: some hardware decoders cap out below the stream size and
 * reject it at SPS-parse time (e.g. `OMX.sprd.hevc.decoder` on Unisoc faults
 * with `signalError(0x80001020)` on a 2K HEVC stream while reporting a lower
 * max). When the hardware decoder can't do the size we fall back to the
 * platform software decoder, which has no such cap. Software AVC/HEVC decoders
 * ship with Android and are reached through MediaCodec — no bundled native code.
 */
internal object VideoDecoderFactory {
    private const val TAG = "VideoDecoderFactory"

    fun createForSize(
        mime: String,
        width: Int,
        height: Int,
        preferSoftware: Boolean = false,
    ): MediaCodec {
        val options = enumerate(mime, width, height)
        options.forEach {
            Log.i(TAG, "  candidate ${it.name} hw=${it.hardware} supports(${width}x$height)=${it.supportsSize}")
        }
        val name = chooseDecoder(options, preferSoftware)
        if (name != null) {
            val advertised = options.first { it.name == name }.supportsSize
            if (advertised) {
                Log.i(TAG, "selected $name for $mime ${width}x$height (preferSoftware=$preferSoftware)")
            } else {
                Log.w(TAG, "no decoder advertises $mime ${width}x$height; trying $name (software) anyway")
            }
            runCatching { return MediaCodec.createByCodecName(name) }
                .onFailure { Log.w(TAG, "createByCodecName($name) failed: ${it.message}") }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun enumerate(mime: String, width: Int, height: Int): List<DecoderOption> {
        val infos = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        }.getOrNull() ?: return emptyList()

        return infos.asSequence()
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .map { info ->
                val supports = runCatching {
                    info.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(width, height) == true
                }.getOrDefault(false)
                DecoderOption(name = info.name, hardware = isHardware(info), supportsSize = supports)
            }
            .toList()
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            // Pre-Q heuristic: Google/AOSP software components are named this way.
            !info.name.startsWith("OMX.google", ignoreCase = true) &&
                !info.name.startsWith("c2.android", ignoreCase = true)
        }
}
