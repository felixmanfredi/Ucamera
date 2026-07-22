package com.trediresearch.ucamera.video

/**
 * Spacchetta i pacchetti RTP contenenti H264 (payload type dinamico) e
 * ricostruisce le NAL unit complete (gestendo la frammentazione FU-A),
 * emettendole in formato Annex-B (con start code 0x00000001) pronte per
 * essere date in pasto a MediaCodec.
 *
 * Stessa logica dello script Python usato per il debug via ffplay.
 */
class RtpH264Depacketizer(
    private val onNalUnit: (ByteArray) -> Unit
) {
    private val startCode = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    private var fuBuffer: ByteArray? = null

    fun feed(rtpPacket: ByteArray) {
        val parsed = parseRtp(rtpPacket) ?: return
        val payload = parsed

        if (payload.isEmpty()) return
        val nalHeader = payload[0]
        val nalType = nalHeader.toInt() and 0x1F

        when {
            nalType in 1..23 -> {
                // NAL unit singola, non frammentata
                onNalUnit(startCode + payload)
            }
            nalType == 28 -> {
                // FU-A: fragmentation unit
                if (payload.size < 2) return
                val fuHeader = payload[1]
                val startBit = (fuHeader.toInt() and 0x80) != 0
                val endBit = (fuHeader.toInt() and 0x40) != 0
                val originalNalType = fuHeader.toInt() and 0x1F
                val fuPayload = payload.copyOfRange(2, payload.size)

                if (startBit) {
                    val reconstructedHeader = ((nalHeader.toInt() and 0xE0) or originalNalType).toByte()
                    fuBuffer = byteArrayOf(reconstructedHeader) + fuPayload
                } else if (fuBuffer != null) {
                    fuBuffer = fuBuffer!! + fuPayload
                }

                if (endBit && fuBuffer != null) {
                    onNalUnit(startCode + fuBuffer!!)
                    fuBuffer = null
                }
            }
            // altri tipi (es. 24 STAP-A) non gestiti in questa versione minimale
        }
    }

    /** Ritorna il payload RTP (dopo header fisso + eventuali CSRC/extension), o null se pacchetto troppo corto. */
    private fun parseRtp(data: ByteArray): ByteArray? {
        if (data.size < 12) return null
        val byte0 = data[0].toInt() and 0xFF
        val cc = byte0 and 0x0F
        val ext = (byte0 shr 4) and 0x01

        var headerLen = 12 + cc * 4
        if (ext == 1) {
            if (data.size < headerLen + 4) return null
            val extLenWords = ((data[headerLen + 2].toInt() and 0xFF) shl 8) or
                               (data[headerLen + 3].toInt() and 0xFF)
            headerLen += 4 + extLenWords * 4
        }

        if (data.size <= headerLen) return null
        return data.copyOfRange(headerLen, data.size)
    }
}
