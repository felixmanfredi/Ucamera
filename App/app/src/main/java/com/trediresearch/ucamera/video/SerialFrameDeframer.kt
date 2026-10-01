package com.trediresearch.ucamera.video

import com.trediresearch.ucamera.webserver.SerialFrame
import com.trediresearch.ucamera.webserver.SerialFrameReader

/**
 * Ricostruisce i pacchetti della corsia video FPV dal flusso di byte della seriale,
 * secondo il framing dell'ESP32 (`0xAA 0x55` + LEN uint16 LE + payload + XOR del payload).
 * Il formato non e' modificabile: il firmware resta quello che e'.
 *
 * Rispetto all'implementazione precedente cambia solo il *come* si legge, delegando a
 * [SerialFrameReader], e si correggono due difetti che stavano entrambi sul thread che
 * deve drenare la UART:
 *
 *  - su checksum errato si ripartiva **dopo** il frame scartato invece che da sync + 1,
 *    quindi un frame valido che iniziasse dentro la finestra di un falso aggancio andava
 *    perso (su lunghezza implausibile si riprovava, ma saltando 2 byte alla volta);
 *  - `buffer += nuoviByte` seguito da una `findSync()` che riscandiva da capo rendeva il
 *    deframer **quadratico**: circa 18 riallocazioni e 18 scansioni complete per ogni
 *    frame da ~36 KB.
 */
class SerialFrameDeframer(
    private val onPacket: (ByteArray) -> Unit
) {
    companion object {
        // L'ESP32 ricompone i chunk UDP e inoltra un frame intero per pacchetto seriale,
        // fino a FPV_MAX_CHUNKS * FPV_MAX_CHUNK_LEN (~35,8 KB).
        private const val MAX_PAYLOAD = 40000
    }

    // Nessun filtro di plausibilita' sul contenuto: a differenza delle risposte REST, che
    // iniziano sempre con "<codice>\n", un frame video e' binario arbitrario. Restano la
    // validazione della lunghezza e il checksum.
    private val reader = SerialFrameReader(
        SerialFrame.FPV_SYNC_0,
        SerialFrame.FPV_SYNC_1,
        MAX_PAYLOAD,
    ) { payload ->
        onPacket(payload)
    }

    fun feed(newBytes: ByteArray, len: Int) {
        reader.feed(newBytes, len)
    }

    fun reset() {
        reader.reset()
    }
}
