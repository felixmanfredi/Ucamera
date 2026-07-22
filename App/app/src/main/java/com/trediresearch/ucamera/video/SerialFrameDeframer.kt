package com.trediresearch.ucamera.video

import android.util.Log

/**
 * Ricostruisce i singoli pacchetti RTP dal flusso di byte continuo che
 * arriva dalla seriale, secondo il framing definito nel firmware ESP32:
 *
 *   [0]     SYNC_0   = 0xAA
 *   [1]     SYNC_1   = 0x55
 *   [2..3]  LEN      = lunghezza payload (uint16, little-endian)
 *   [4..]   PAYLOAD  = pacchetto RTP grezzo
 *   [ultimo] CHECKSUM = XOR di tutti i byte del payload
 *
 * Uso: alimenta i byte grezzi letti dalla seriale con feed(), e ogni volta
 * che un frame completo e valido viene ricostruito, onPacket viene invocato
 * con il pacchetto RTP grezzo.
 */
class SerialFrameDeframer(
    private val onPacket: (ByteArray) -> Unit
) {
    private val SYNC_0: Byte = 0xAA.toByte()
    private val SYNC_1: Byte = 0x55.toByte()
    private val MAX_PAYLOAD = 1500

    // Buffer di accumulo per i byte che arrivano frammentati tra chiamate feed()
    private var buffer = ByteArray(0)

    fun feed(newBytes: ByteArray, len: Int) {
        buffer += newBytes.copyOfRange(0, len)
        parseBuffer()
    }

    private fun parseBuffer() {
        while (true) {
            // 1. Trova SYNC_0 SYNC_1 nel buffer, scarta tutto quello che precede
            val syncIndex = findSync()
            if (syncIndex < 0) {
                // Nessun sync trovato: tieni solo l'ultimo byte (potrebbe essere
                // l'inizio di un SYNC_0 che sara' completato dal prossimo feed())
                if (buffer.isNotEmpty()) {
                    buffer = buffer.copyOfRange(buffer.size - 1, buffer.size)
                }
                return
            }
            if (syncIndex > 0) {
                buffer = buffer.copyOfRange(syncIndex, buffer.size)
            }

            // 2. Servono almeno 4 byte per header completo (sync+len)
            if (buffer.size < 4) return

            val len = (buffer[2].toInt() and 0xFF) or ((buffer[3].toInt() and 0xFF) shl 8)
            if (len <= 0 || len > MAX_PAYLOAD) {
                // Lunghezza non plausibile: probabilmente un falso sync, scarta 2 byte e riprova
                buffer = buffer.copyOfRange(2, buffer.size)
                continue
            }

            val totalFrameLen = 4 + len + 1  // header + payload + checksum
            if (buffer.size < totalFrameLen) return  // aspetta altri byte

            val payload = buffer.copyOfRange(4, 4 + len)
            val receivedChecksum = buffer[4 + len]
            var computedChecksum: Byte = 0
            for (b in payload) computedChecksum = (computedChecksum.toInt() xor b.toInt()).toByte()

            if (computedChecksum == receivedChecksum) {
                onPacket(payload)
               // Log.d("SerialFrameDeframer", "Ricevuto pacchetto di lunghezza $len")
            }
            // altrimenti: pacchetto corrotto, scartato silenziosamente (e' UDP, va bene perderlo)

            buffer = buffer.copyOfRange(totalFrameLen, buffer.size)
        }
    }

    private fun findSync(): Int {
        for (i in 0 until buffer.size - 1) {
            if (buffer[i] == SYNC_0 && buffer[i + 1] == SYNC_1) return i
        }
        return -1
    }
}
