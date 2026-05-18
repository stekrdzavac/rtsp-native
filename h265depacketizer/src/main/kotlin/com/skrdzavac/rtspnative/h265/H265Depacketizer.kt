package com.skrdzavac.rtspnative.h265

import com.skrdzavac.rtspnative.core.AccessUnit
import com.skrdzavac.rtspnative.core.RtpPacket
import java.io.ByteArrayOutputStream

/**
 * Reassembles H.265 NAL units from RTP per RFC 7798.
 *
 * Supports:
 *   - Single NAL units (types 0–47)
 *   - AP (Aggregation Packets, type 48)
 *   - FU (Fragmentation Units, type 49)
 *
 * PACI (type 50) is silently dropped — rare in IP cameras.
 *
 * Like the H.264 sibling, one [AccessUnit.Video] is emitted per source
 * picture (RTP marker bit or timestamp change). VPS/SPS/PPS NALs are
 * exposed via [parameterSets] so the decoder can configure MediaCodec
 * without relying on SDP sprop-vps/sps/pps.
 *
 * Annex-B output: each NAL prepended with `00 00 00 01`.
 *
 * Note: Stage 1 does NOT honor sprop-max-don-diff > 0 (decoding-order
 * differences). Most IP cameras don't reorder, and the spec lets
 * receivers ignore DON unless sprop-max-don-diff is explicitly set.
 */
class H265Depacketizer {

    @Volatile
    var parameterSets: ParameterSets? = null
        private set

    private val accumulator = ByteArrayOutputStream()
    private val fragment = ByteArrayOutputStream()
    private var inFragment = false
    private var currentTimestamp: Long = -1
    private var currentHasKeyframe = false

    fun depacketize(packet: RtpPacket): List<AccessUnit.Video> {
        if (packet.payloadLength < 2) return emptyList()

        val emitted = mutableListOf<AccessUnit.Video>()

        if (currentTimestamp >= 0 && packet.timestamp != currentTimestamp && accumulator.size() > 0) {
            emitted += flush()
        }
        currentTimestamp = packet.timestamp

        val payload = packet.buffer
        val offset = packet.payloadOffset
        val length = packet.payloadLength
        val nalType = H265NalType.typeOf(payload[offset].toInt() and 0xFF)

        when {
            nalType == H265NalType.AP -> appendAp(payload, offset, length)
            nalType == H265NalType.FU -> appendFu(payload, offset, length)
            nalType in 0..47 -> appendSingle(payload, offset, length, nalType)
            // PACI (50) and other reserved types fall through silently.
        }

        if (packet.marker && accumulator.size() > 0) {
            emitted += flush()
        }
        return emitted
    }

    private fun appendSingle(payload: ByteArray, offset: Int, length: Int, nalType: Int) {
        writeNalAnnexB(payload, offset, length)
        markByType(nalType, payload, offset, length)
    }

    private fun appendAp(payload: ByteArray, offset: Int, length: Int) {
        // PayloadHdr is 2 bytes; aggregated NALs follow, each as: 2-byte size + NAL bytes.
        var cursor = offset + 2
        val end = offset + length
        while (cursor + 2 <= end) {
            val nalSize = ((payload[cursor].toInt() and 0xFF) shl 8) or
                (payload[cursor + 1].toInt() and 0xFF)
            cursor += 2
            if (nalSize == 0 || cursor + nalSize > end) return
            val nalType = H265NalType.typeOf(payload[cursor].toInt() and 0xFF)
            writeNalAnnexB(payload, cursor, nalSize)
            markByType(nalType, payload, cursor, nalSize)
            cursor += nalSize
        }
    }

    private fun appendFu(payload: ByteArray, offset: Int, length: Int) {
        // Layout: PayloadHdr (2 bytes, type=49) | FU header (1 byte) | FU payload
        if (length < 3) return
        val payloadHdr0 = payload[offset].toInt() and 0xFF
        val payloadHdr1 = payload[offset + 1].toInt() and 0xFF
        val fuHeader = payload[offset + 2].toInt() and 0xFF

        val start = (fuHeader and 0x80) != 0
        val end = (fuHeader and 0x40) != 0
        val fuType = fuHeader and 0x3F

        if (start) {
            fragment.reset()
            // Reconstructed NAL header: take PayloadHdr, replace type with fuType.
            // PayloadHdr byte0: F (1 bit) | Type (6 bits) | LayerId-hi (1 bit)
            // Need to replace bits 1-6 of byte0 with fuType.
            val rebuiltByte0 = (payloadHdr0 and 0x81) or (fuType shl 1)
            fragment.write(rebuiltByte0)
            fragment.write(payloadHdr1)
            inFragment = true
        }
        if (!inFragment) return
        // Append FU payload (skip 2-byte PayloadHdr + 1-byte FU header)
        fragment.write(payload, offset + 3, length - 3)

        if (end) {
            val nal = fragment.toByteArray()
            writeNalAnnexB(nal, 0, nal.size)
            markByType(fuType, nal, 0, nal.size)
            fragment.reset()
            inFragment = false
        }
    }

    private fun writeNalAnnexB(nal: ByteArray, offset: Int, length: Int) {
        accumulator.write(0); accumulator.write(0); accumulator.write(0); accumulator.write(1)
        accumulator.write(nal, offset, length)
    }

    private fun markByType(nalType: Int, nal: ByteArray, offset: Int, length: Int) {
        if (H265NalType.isKeyframe(nalType)) currentHasKeyframe = true
        when (nalType) {
            H265NalType.VPS_NUT -> {
                val copy = nal.copyOfRange(offset, offset + length)
                parameterSets = (parameterSets ?: ParameterSets()).copy(vps = copy)
            }
            H265NalType.SPS_NUT -> {
                val copy = nal.copyOfRange(offset, offset + length)
                parameterSets = (parameterSets ?: ParameterSets()).copy(sps = copy)
            }
            H265NalType.PPS_NUT -> {
                val copy = nal.copyOfRange(offset, offset + length)
                parameterSets = (parameterSets ?: ParameterSets()).copy(pps = copy)
            }
        }
    }

    private fun flush(): AccessUnit.Video {
        val au = AccessUnit.Video(
            ptsRtp = currentTimestamp,
            payload = accumulator.toByteArray(),
            isKeyframe = currentHasKeyframe,
        )
        accumulator.reset()
        currentHasKeyframe = false
        return au
    }

    fun seedParameterSets(vps: ByteArray?, sps: ByteArray?, pps: ByteArray?) {
        parameterSets = ParameterSets(vps = vps, sps = sps, pps = pps)
    }

    data class ParameterSets(
        val vps: ByteArray? = null,
        val sps: ByteArray? = null,
        val pps: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean = other is ParameterSets &&
            (vps?.contentEquals(other.vps) ?: (other.vps == null)) &&
            (sps?.contentEquals(other.sps) ?: (other.sps == null)) &&
            (pps?.contentEquals(other.pps) ?: (other.pps == null))

        override fun hashCode(): Int {
            var r = vps?.contentHashCode() ?: 0
            r = r * 31 + (sps?.contentHashCode() ?: 0)
            r = r * 31 + (pps?.contentHashCode() ?: 0)
            return r
        }
    }
}
