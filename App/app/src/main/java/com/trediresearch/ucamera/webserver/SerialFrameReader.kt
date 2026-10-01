package com.trediresearch.ucamera.webserver

/**
 * Framing seriale dell'ESP32 (SkydroidSerial2Net). **Il formato e' immutabile**: il
 * firmware non si tocca, quindi qui si puo' solo leggerlo meglio, non cambiarlo.
 *
 * ```
 * [0]      SYNC_0    A1 richiesta | B1 risposta | AA video FPV
 * [1]      SYNC_1    A2           | B2          | 55
 * [2..3]   LEN       uint16 little-endian
 * [4..]    PAYLOAD
 * [ultimo] CHECKSUM  XOR a 8 bit del solo payload
 * ```
 */
object SerialFrame {
    const val REST_SYNC_0: Byte = 0xA1.toByte()
    const val REST_SYNC_1: Byte = 0xA2.toByte()
    const val REST_RESP_SYNC_0: Byte = 0xB1.toByte()
    const val REST_RESP_SYNC_1: Byte = 0xB2.toByte()
    const val FPV_SYNC_0: Byte = 0xAA.toByte()
    const val FPV_SYNC_1: Byte = 0x55.toByte()

    const val HEADER_SIZE = 4
    const val TRAILER_SIZE = 1

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

    /**
     * Il payload di una risposta REST e' sempre `"<codice>\n"` seguito dal corpo: codice
     * decimale, eventualmente negativo (`"-1\nERRORE_HTTPCLIENT: ..."` quando l'ESP32 non
     * e' nemmeno riuscito a fare la richiesta).
     *
     * Serve come filtro contro i falsi agganci: le corsie condividono il filo senza
     * byte-stuffing e `B1 B2` capita per caso dentro i dati binari del video, dove i byte
     * successivi sono casuali e non rispettano quasi mai questo schema. Costa pochi
     * confronti e si applica prima del checksum, che e' lineare sul payload.
     */
    fun looksLikeRestResponse(buf: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 2) return false
        var i = offset
        val end = offset + minOf(length, 8) // "-65535\n" sta abbondantemente in 8 byte
        if (buf[i] == '-'.code.toByte()) i++
        var digits = 0
        while (i < end) {
            val c = buf[i].toInt()
            if (c >= '0'.code && c <= '9'.code) { digits++; i++; continue }
            return c == '\n'.code && digits > 0
        }
        return false
    }
}

/**
 * Estrae i frame di UNA corsia dal flusso di byte della seriale.
 *
 * Il parser precedente era una macchina a stati che, agganciato un SYNC, leggeva LEN e
 * **consumava quei byte senza possibilita' di ripensamento**. Dentro un frame video da
 * ~36 KB un falso `B1 B2` capita spesso: LEN diventava un numero arbitrario fino a 65535
 * e il parser si mangiava tutto quello che passava, **inclusa una risposta vera che
 * iniziasse in quella finestra**. Il checksum scartava poi il frame fasullo, ma la
 * risposta vera era ormai persa e il chiamante andava in timeout.
 *
 * Qui ogni SYNC e' solo un *candidato*: finche' non sono valide sia la lunghezza sia il
 * checksum non si consuma nulla, e in caso di scarto la scansione riprende da
 * **SYNC + 1 byte**, non dalla fine del frame fasullo. Un frame valido contenuto nella
 * finestra di un falso aggancio viene quindi sempre ritrovato.
 *
 * Costa un buffer di accumulo, ma e' comunque molto piu' leggero di prima sulla corsia
 * video, dove il deframer faceva `buffer += nuoviByte` e riscandiva da capo a ogni
 * feed(): circa 18 riallocazioni e 18 scansioni complete per frame, sul thread che deve
 * drenare la UART.
 */
class SerialFrameReader(
    private val sync0: Byte,
    private val sync1: Byte,
    private val maxPayload: Int,
    private val isPlausiblePayload: ((ByteArray, Int, Int) -> Boolean)? = null,
    // Iniettabile solo per i test: il timeout sul candidato dipende dall'orologio.
    private val nowMs: () -> Long = System::currentTimeMillis,
    // Ultimo parametro perche' i chiamanti lo passano come lambda finale.
    private val onFrame: (ByteArray) -> Unit,
) {

    companion object {
        // Quanto si aspetta un candidato prima di considerarlo falso. Un aggancio
        // sbagliato con una lunghezza grande ma plausibile tiene il parser in attesa di
        // byte che non arriveranno mai: con il flusso video attivo si risolve da solo in
        // poche decine di ms (continuano ad arrivare byte che fanno fallire il checksum),
        // ma a flusso fermo resterebbe bloccato, e con lui una risposta vera che si trovi
        // in quella finestra. Due secondi sono abbondanti per qualunque frame reale e
        // restano molto sotto i 20s di timeout della richiesta.
        private const val CANDIDATE_TIMEOUT_MS = 2000L
    }

    // Finestra di accumulo [start, end). Dimensionata per contenere il frame piu' grande
    // rappresentabile piu' un margine: oltre quel punto un candidato non puo' piu'
    // completarsi e viene abbandonato.
    private val capacity = maxPayload + SerialFrame.HEADER_SIZE + SerialFrame.TRAILER_SIZE + 2048
    private var buf = ByteArray(capacity)
    private var start = 0
    private var end = 0

    // Da dove ricominciare a cercare un SYNC. Avanza di 1 byte a ogni candidato scartato.
    private var searchFrom = 0

    // Candidato in attesa di byte: posizione assoluta (contatore monotono, non indice nel
    // buffer, che la compattazione sposta) e istante in cui ha iniziato ad aspettare.
    private var pendingCandidate = -1L
    private var pendingSince = 0L
    private var consumedTotal = 0L

    fun feed(data: ByteArray, count: Int) {
        var offset = 0
        var remaining = count
        while (remaining > 0) {
            if (end == capacity) {
                compact()
                if (end == capacity) {
                    // Finestra piena di soli candidati mai completati: non puo' esserci
                    // un frame valido li' dentro, si riparte puliti invece di bloccarsi.
                    start = 0; end = 0; searchFrom = 0
                }
            }
            val n = minOf(remaining, capacity - end)
            System.arraycopy(data, offset, buf, end, n)
            end += n
            offset += n
            remaining -= n
            scan()
        }
    }

    fun reset() {
        start = 0
        end = 0
        searchFrom = 0
        pendingCandidate = -1L
    }

    private fun scan() {
        while (true) {
            val syncIdx = findSync(maxOf(searchFrom, start))
            if (syncIdx < 0) {
                // Nessun candidato: si tiene solo l'ultimo byte, che potrebbe essere la
                // prima meta' di un SYNC spezzato fra due feed().
                start = if (end > start) end - 1 else end
                searchFrom = start
                compactIfNeeded()
                return
            }
            start = syncIdx
            searchFrom = syncIdx

            if (end - syncIdx < SerialFrame.HEADER_SIZE) {
                compactIfNeeded()
                return // header incompleto: si aspettano altri byte
            }

            val len = (buf[syncIdx + 2].toInt() and 0xFF) or
                    ((buf[syncIdx + 3].toInt() and 0xFF) shl 8)
            if (len <= 0 || len > maxPayload) {
                searchFrom = syncIdx + 1
                continue
            }

            val total = SerialFrame.HEADER_SIZE + len + SerialFrame.TRAILER_SIZE
            if (end - syncIdx < total) {
                // Potrebbe essere vero e incompleto, oppure falso: in entrambi i casi non
                // si consuma nulla. Se si rivelera' falso, al prossimo giro il checksum
                // fallira' e si ripartira' da syncIdx + 1 senza aver perso byte.
                if (!noteWaiting(syncIdx)) {
                    // Aspetta da troppo: trattalo come falso aggancio e vai avanti.
                    searchFrom = syncIdx + 1
                    pendingCandidate = -1L
                    continue
                }
                compactIfNeeded()
                return
            }
            pendingCandidate = -1L

            val payloadAt = syncIdx + SerialFrame.HEADER_SIZE
            val plausible = isPlausiblePayload?.invoke(buf, payloadAt, len) ?: true
            if (plausible && checksumOk(payloadAt, len)) {
                onFrame(buf.copyOfRange(payloadAt, payloadAt + len))
                start = syncIdx + total
                searchFrom = start
            } else {
                searchFrom = syncIdx + 1
            }
        }
    }

    /** false se il candidato in [syncIdx] sta aspettando da piu' di CANDIDATE_TIMEOUT_MS. */
    private fun noteWaiting(syncIdx: Int): Boolean {
        val absolute = consumedTotal + syncIdx
        if (pendingCandidate != absolute) {
            pendingCandidate = absolute
            pendingSince = nowMs()
            return true
        }
        return nowMs() - pendingSince <= CANDIDATE_TIMEOUT_MS
    }

    private fun checksumOk(payloadAt: Int, len: Int): Boolean {
        var computed: Byte = 0
        for (i in payloadAt until payloadAt + len) {
            computed = (computed.toInt() xor buf[i].toInt()).toByte()
        }
        return computed == buf[payloadAt + len]
    }

    private fun findSync(from: Int): Int {
        var i = maxOf(from, 0)
        while (i + 1 < end) {
            if (buf[i] == sync0 && buf[i + 1] == sync1) return i
            i++
        }
        return -1
    }

    private fun compactIfNeeded() {
        if (start > capacity / 2) compact()
    }

    private fun compact() {
        if (start == 0) return
        val len = end - start
        System.arraycopy(buf, start, buf, 0, len)
        searchFrom -= start
        if (searchFrom < 0) searchFrom = 0
        consumedTotal += start
        start = 0
        end = len
    }
}
