package com.nittbit.rtspkit.h264

import com.nittbit.rtspkit.core.AccessUnit
import com.nittbit.rtspkit.core.RtpPacket
import java.io.ByteArrayOutputStream

/**
 * Reassembles H.264 NAL units from RTP per RFC 6184.
 *
 * Supports single NAL (types 1–23), STAP-A (24), and FU-A (28). Other
 * aggregation/fragmentation types are silently dropped — they're rare in
 * IP-camera streams.
 *
 * One [AccessUnit.Video] is emitted per source picture, identified by RTP
 * marker bit or timestamp change. SPS/PPS NALs are also kept in the
 * [parameterSets] holder so the decoder can configure MediaCodec without
 * relying on `sprop-parameter-sets` from SDP.
 */
class H264Depacketizer {

    @Volatile
    var parameterSets: ParameterSets? = null
        private set

    private val accumulator = ByteArrayOutputStream()
    private val fragment = ByteArrayOutputStream()
    private var fragmentNalHeader: Byte = 0
    private var inFragment = false
    private var currentTimestamp: Long = -1
    private var currentHasKeyframe = false

    /**
     * Feed one RTP packet. Returns zero or one access units. Most packets
     * return zero; the marker bit (or a timestamp change on the *next*
     * packet) triggers emission.
     */
    fun depacketize(packet: RtpPacket): List<AccessUnit.Video> {
        if (packet.payloadLength < 1) return emptyList()

        val emitted = mutableListOf<AccessUnit.Video>()

        // Flush the previously accumulated AU when the timestamp ticks over.
        if (currentTimestamp >= 0 && packet.timestamp != currentTimestamp && accumulator.size() > 0) {
            emitted += flush()
        }
        currentTimestamp = packet.timestamp

        val payload = packet.buffer
        val offset = packet.payloadOffset
        val length = packet.payloadLength
        val nalHeader = payload[offset].toInt() and 0xFF
        val nalType = nalHeader and 0x1F

        when {
            nalType in 1..23 -> appendSingle(payload, offset, length, nalType)
            nalType == H264NalType.STAP_A -> appendStapA(payload, offset, length)
            nalType == H264NalType.FU_A -> appendFuA(payload, offset, length)
            // FU-B, STAP-B, MTAP are unsupported — silently drop.
        }

        if (packet.marker && accumulator.size() > 0) {
            emitted += flush()
        }
        return emitted
    }

    private fun appendSingle(payload: ByteArray, offset: Int, length: Int, nalType: Int) {
        AnnexB.writeNal(accumulator, payload, offset, length)
        markKeyframeOrParameterSet(nalType, payload, offset, length)
    }

    private fun appendStapA(payload: ByteArray, offset: Int, length: Int) {
        var cursor = offset + 1
        val end = offset + length
        while (cursor + 2 <= end) {
            val nalSize = ((payload[cursor].toInt() and 0xFF) shl 8) or
                (payload[cursor + 1].toInt() and 0xFF)
            cursor += 2
            if (nalSize == 0 || cursor + nalSize > end) return
            val nalType = payload[cursor].toInt() and 0x1F
            AnnexB.writeNal(accumulator, payload, cursor, nalSize)
            markKeyframeOrParameterSet(nalType, payload, cursor, nalSize)
            cursor += nalSize
        }
    }

    private fun appendFuA(payload: ByteArray, offset: Int, length: Int) {
        if (length < 2) return
        val fuIndicator = payload[offset].toInt() and 0xFF
        val fuHeader = payload[offset + 1].toInt() and 0xFF
        val start = (fuHeader and 0x80) != 0
        val end = (fuHeader and 0x40) != 0
        val nalType = fuHeader and 0x1F

        if (start) {
            fragment.reset()
            // Reconstruct the original NAL header: top 3 bits from FU indicator, low 5 bits from FU header
            fragmentNalHeader = ((fuIndicator and 0xE0) or nalType).toByte()
            fragment.write(fragmentNalHeader.toInt() and 0xFF)
            inFragment = true
        }
        if (!inFragment) return
        // Append fragment payload (skip 2-byte FU indicator + FU header)
        fragment.write(payload, offset + 2, length - 2)

        if (end) {
            val nal = fragment.toByteArray()
            AnnexB.writeNal(accumulator, nal)
            markKeyframeOrParameterSet(nalType, nal, 0, nal.size)
            fragment.reset()
            inFragment = false
        }
    }

    private fun markKeyframeOrParameterSet(nalType: Int, nal: ByteArray, offset: Int, length: Int) {
        when (nalType) {
            H264NalType.SLICE_IDR -> currentHasKeyframe = true
            H264NalType.SPS -> {
                val copy = nal.copyOfRange(offset, offset + length)
                parameterSets = (parameterSets ?: ParameterSets()).copy(sps = copy)
                currentHasKeyframe = true
            }
            H264NalType.PPS -> {
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

    fun seedParameterSets(sps: ByteArray?, pps: ByteArray?) {
        parameterSets = ParameterSets(sps = sps, pps = pps)
    }

    data class ParameterSets(val sps: ByteArray? = null, val pps: ByteArray? = null) {
        override fun equals(other: Any?): Boolean = other is ParameterSets &&
            (sps?.contentEquals(other.sps) ?: (other.sps == null)) &&
            (pps?.contentEquals(other.pps) ?: (other.pps == null))
        override fun hashCode(): Int = (sps?.contentHashCode() ?: 0) * 31 + (pps?.contentHashCode() ?: 0)
    }
}
