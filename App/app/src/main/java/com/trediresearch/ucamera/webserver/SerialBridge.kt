package com.trediresearch.ucamera.webserver

import android.content.Context
import android.util.Log
import com.trediresearch.ucamera.video.SerialPortConnection
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

class SerialBridge(private val context: Context, private val serialPort: SerialPortConnection? = null) {

    companion object {
        private const val TAG = "SerialBridge"

        // Massimo rappresentabile dal campo lunghezza a 2 byte del framing dell'ESP32.
        private const val MAX_PAYLOAD = 65535
    }

    // Risposte in attesa, keyed by generazione (vedi sendRequestAndAwait).
    private val pendingResponses = ConcurrentHashMap<Long, CompletableFuture<Pair<Int, ByteArray>>>()

    private val reader = SerialFrameReader(
        SerialFrame.REST_RESP_SYNC_0,
        SerialFrame.REST_RESP_SYNC_1,
        MAX_PAYLOAD,
        SerialFrame::looksLikeRestResponse,
    ) { payload ->
        handleResponseFrame(payload)
    }

    private val delegate = object : SerialPortConnection.Delegate {

        override fun connect() {

        }

        override fun received(param1ArrayOfbyte: ByteArray, param1Int: Int) {
            try {
                if (param1Int > 0) {
                    reader.feed(param1ArrayOfbyte, param1Int)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore lettura seriale", e)
            }

        }
    }

    // addDelegate() lavora su una CopyOnWriteArrayList, che NON deduplica: senza questo
    // flag un doppio connect() registrerebbe due volte lo stesso delegate e ogni risposta
    // verrebbe parsata due volte. connect()/disconnect() devono essere idempotenti perche'
    // Webserver.init()/shutdown() possono essere chiamate piu' volte nel ciclo di vita
    // della finestra (ogni updateConnection() ricostruisce il Webserver).
    private var connected = false

    @Synchronized
    fun connect(): Boolean {
        if (connected) return true
        val port = serialPort ?: return false
        port.addDelegate(delegate)
        connected = true
        return true
    }

    @Synchronized
    fun disconnect() {
        if (!connected) return
        serialPort?.removeDelegate(delegate)
        connected = false
        reader.reset()
        // Non lasciare appese le richieste in volo: senza questo chi sta aspettando
        // resterebbe bloccato fino al timeout di 20s su un bridge che non ascolta piu'.
        val orphans = pendingResponses.values.toList()
        pendingResponses.clear()
        for (f in orphans) {
            f.completeExceptionally(IOException("SerialBridge chiuso durante la richiesta"))
        }
    }

    // Una richiesta alla volta: il filo e' uno solo e il firmware serve una richiesta per
    // volta (HTTP incluso) prima di rileggere la seriale, quindi non c'e' parallelismo
    // reale da guadagnare.
    //
    // Il lock pero' serializza i MITTENTI, non il filo: la risposta a una richiesta
    // scaduta puo' arrivare mentre ne e' gia' partita un'altra. Con l'unico slot fisso di
    // prima quella risposta tardiva completava la richiesta SBAGLIATA - p.es. uno scatto
    // che riceveva il JSON di un polling andato in timeout, e quindi un'immagine
    // illeggibile.
    //
    // Il protocollo non ha un identificativo di richiesta e non e' modificabile, quindi
    // non si puo' sapere A CHI appartenga una risposta tardiva. Si puo' pero' sapere che
    // NON appartiene a quella in corso: ogni invio incrementa una generazione, e
    // handleResponseFrame completa solo quella corrente. Il resto viene scartato.
    private val requestLock = Any()
    private val generation = AtomicLong(0)

    @Volatile
    private var currentGeneration = -1L

    // 20s: deve restare comodamente sopra il timeout HTTP dell'ESP32 (15s, vedi
    // main.cpp handleRestRequest) + margine per il giro seriale/radio Skydroid.
    fun sendRequestAndAwait(payload: ByteArray, timeoutMs: Long = 20000): Pair<Int, ByteArray> {
        synchronized(requestLock) {
            val port = serialPort ?: throw IOException("Porta seriale non disponibile")
            val stream = port.outputStream ?: throw IOException("Stream seriale chiuso")
            if (payload.size > MAX_PAYLOAD) {
                throw IOException("Richiesta troppo grande per il framing (${payload.size} byte)")
            }

            val gen = generation.incrementAndGet()
            val future = CompletableFuture<Pair<Int, ByteArray>>()
            pendingResponses[gen] = future
            currentGeneration = gen

            try {
                val frame = SerialFrame.encode(SerialFrame.REST_SYNC_0, SerialFrame.REST_SYNC_1, payload)
                stream.write(frame)
                stream.flush()
            } catch (e: Exception) {
                pendingResponses.remove(gen)
                currentGeneration = -1L
                throw IOException("Invio richiesta fallito: " + e.message, e)
            }

            return try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                throw IOException("Timeout risposta dall'ESP32", e)
            } finally {
                // In ogni caso questa generazione non e' piu' attesa: se la risposta
                // arriva adesso, handleResponseFrame non trovera' nulla da completare.
                pendingResponses.remove(gen)
                currentGeneration = -1L
            }
        }
    }

    // Il corpo puo' essere binario (es. JPEG per /camera/capture): solo il prefisso
    // "codice\n" e' testo, quindi si decodifica come ASCII solo quella parte e il resto
    // resta ByteArray grezzo, senza mai passare per un round-trip UTF-8 che lo corromperebbe.
    private fun handleResponseFrame(payload: ByteArray) {
        val idx = payload.indexOf('\n'.code.toByte())
        if (idx < 0) return
        val code = String(payload, 0, idx, Charsets.US_ASCII).trim().toIntOrNull() ?: -1
        val body = payload.copyOfRange(idx + 1, payload.size)

        val gen = currentGeneration
        val future = if (gen >= 0) pendingResponses.remove(gen) else null
        if (future == null) {
            Log.w(TAG, "Risposta fuori contesto scartata (HTTP $code, ${body.size} byte): " +
                    "nessuna richiesta in attesa")
            return
        }
        future.complete(code to body)
    }
}
