package com.trediresearch.ucamera.webserver

object SerialFrame {
    const val REST_SYNC_0: Byte = 0xA1.toByte()
    const val REST_SYNC_1: Byte = 0xA2.toByte()
    const val REST_RESP_SYNC_0: Byte = 0xB1.toByte()
    const val REST_RESP_SYNC_1: Byte = 0xB2.toByte()

    fun encode(sync0: Byte, sync1: Byte, payload: ByteArray): ByteArray {
        var checksum: Byte = 0
        for (b in payload) checksum = (checksum.toInt() xor b.toInt()).toByte()

        val header = byteArrayOf(
            sync0, sync1,
            (payload.size and 0xFF).toByte(),
            ((payload.size shr 8) and 0xFF).toByte()
        )
        return header + payload + byteArrayOf(checksum)
    }
}

class SerialFrameReader(private val onFrame: (sync0: Byte, sync1: Byte, payload: ByteArray) -> Unit) {

    private enum class State { SYNC0, SYNC1, LEN_LOW, LEN_HIGH, PAYLOAD, CHECKSUM }

    private var state = State.SYNC0
    private var sync0: Byte = 0
    private var sync1: Byte = 0
    private var len = 0
    private var idx = 0
    private var checksum: Byte = 0
    private var buffer = ByteArray(4096)

    fun feed(data: ByteArray, count: Int) {
        for (i in 0 until count) {
            val b = data[i]
            when (state) {
                State.SYNC0 -> {
                    if (b == SerialFrame.REST_RESP_SYNC_0) { sync0 = b; state = State.SYNC1 }
                }
                State.SYNC1 -> {
                    state = if (b == SerialFrame.REST_RESP_SYNC_1) { sync1 = b; State.LEN_LOW }
                    else State.SYNC0
                }
                State.LEN_LOW -> { len = b.toInt() and 0xFF; state = State.LEN_HIGH }
                State.LEN_HIGH -> {
                    len = len or ((b.toInt() and 0xFF) shl 8)
                    if (len <= 0 || len > buffer.size) { state = State.SYNC0 }
                    else { idx = 0; checksum = 0; state = State.PAYLOAD }
                }
                State.PAYLOAD -> {
                    buffer[idx++] = b
                    checksum = (checksum.toInt() xor b.toInt()).toByte()
                    if (idx >= len) state = State.CHECKSUM
                }
                State.CHECKSUM -> {
                    if (b == checksum) {
                        onFrame(sync0, sync1, buffer.copyOf(len))
                    }
                    state = State.SYNC0
                }
            }
        }
    }
}