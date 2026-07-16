package com.aifieldcam.app.platform

/**
 * H.264 Annex-B NAL → RTP (RFC 6184) 打包。
 * payload type 96；过大 NAL 用 FU-A 分片。
 */
object RtpPacketizer {

    const val PAYLOAD_TYPE_H264 = 96
    const val MAX_PAYLOAD = 1200

    private const val NAL_TYPE_MASK = 0x1F
    private const val NRI_MASK = 0x60
    private const val FU_A_TYPE = 28

    /**
     * @return 若干完整 RTP 包（含 12 字节头）
     */
    fun packetize(
        annexBNal: ByteArray,
        sequence: Int,
        timestamp: Int,
        ssrc: Int,
        markerOnLast: Boolean = true,
    ): List<ByteArray> {
        val nal = stripStartCode(annexBNal)
        if (nal.isEmpty()) return emptyList()

        if (nal.size <= MAX_PAYLOAD) {
            return listOf(buildRtp(nal, sequence and 0xFFFF, timestamp, ssrc, marker = true))
        }

        val packets = ArrayList<ByteArray>()
        val nalHeader = nal[0].toInt() and 0xFF
        val nri = nalHeader and NRI_MASK
        val nalType = nalHeader and NAL_TYPE_MASK
        val payload = nal.copyOfRange(1, nal.size)
        var offset = 0
        var seq = sequence and 0xFFFF
        var first = true
        while (offset < payload.size) {
            val end = minOf(offset + (MAX_PAYLOAD - 2), payload.size)
            val last = end >= payload.size
            val fuIndicator = (nri or FU_A_TYPE).toByte()
            var fuHeader = nalType
            if (first) fuHeader = fuHeader or 0x80
            if (last) fuHeader = fuHeader or 0x40
            val body = ByteArray(2 + (end - offset))
            body[0] = fuIndicator
            body[1] = fuHeader.toByte()
            System.arraycopy(payload, offset, body, 2, end - offset)
            packets.add(
                buildRtp(
                    body,
                    seq,
                    timestamp,
                    ssrc,
                    marker = last && markerOnLast,
                ),
            )
            seq = (seq + 1) and 0xFFFF
            offset = end
            first = false
        }
        return packets
    }

    fun stripStartCode(data: ByteArray): ByteArray {
        if (data.size >= 4 &&
            data[0] == 0.toByte() && data[1] == 0.toByte() &&
            data[2] == 0.toByte() && data[3] == 1.toByte()
        ) {
            return data.copyOfRange(4, data.size)
        }
        if (data.size >= 3 &&
            data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()
        ) {
            return data.copyOfRange(3, data.size)
        }
        return data
    }

    /** Annex-B 或裸 NAL 的类型（1–23）；配置帧 7=SPS 8=PPS；IDR=5 */
    fun nalType(data: ByteArray): Int {
        val nal = stripStartCode(data)
        if (nal.isEmpty()) return 0
        return nal[0].toInt() and NAL_TYPE_MASK
    }

    fun isParameterSet(data: ByteArray): Boolean {
        val t = nalType(data)
        return t == 7 || t == 8
    }

    fun isIdr(data: ByteArray): Boolean = nalType(data) == 5

    private fun buildRtp(
        payload: ByteArray,
        sequence: Int,
        timestamp: Int,
        ssrc: Int,
        marker: Boolean,
    ): ByteArray {
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte() // V=2
        packet[1] = ((if (marker) 0x80 else 0) or (PAYLOAD_TYPE_H264 and 0x7F)).toByte()
        packet[2] = ((sequence shr 8) and 0xFF).toByte()
        packet[3] = (sequence and 0xFF).toByte()
        packet[4] = ((timestamp shr 24) and 0xFF).toByte()
        packet[5] = ((timestamp shr 16) and 0xFF).toByte()
        packet[6] = ((timestamp shr 8) and 0xFF).toByte()
        packet[7] = (timestamp and 0xFF).toByte()
        packet[8] = ((ssrc shr 24) and 0xFF).toByte()
        packet[9] = ((ssrc shr 16) and 0xFF).toByte()
        packet[10] = ((ssrc shr 8) and 0xFF).toByte()
        packet[11] = (ssrc and 0xFF).toByte()
        System.arraycopy(payload, 0, packet, 12, payload.size)
        return packet
    }
}
