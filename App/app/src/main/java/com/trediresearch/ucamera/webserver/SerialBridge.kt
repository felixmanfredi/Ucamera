package com.trediresearch.ucamera.webserver

import android.content.Context
import android.util.Log
import com.trediresearch.ucamera.video.SerialPortConnection
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class SerialBridge(private val context: Context, private val serialPort: SerialPortConnection? = null) {

    //private var port: UsbSerialPort? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()

    // Coda di risposte in attesa, keyed by requestId
    private val pendingResponses = ConcurrentHashMap<Long, CompletableFuture<Pair<Int, ByteArray>>>()
    private val reader = SerialFrameReader { sync0, sync1, payload ->
        if (sync0 == SerialFrame.REST_RESP_SYNC_0 && sync1 == SerialFrame.REST_RESP_SYNC_1) {
            handleResponseFrame(payload)
        }
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
                //Log.e(TAG, "Errore lettura seriale", e)
            }

        }
    }

    fun connect(): Boolean{
        serialPort?.addDelegate(delegate)
        return true;
    }

    fun disconnect() {
        serialPort?.removeDelegate(delegate)
    }

    /*
    fun connect(): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
            ?: return false

        val connection = manager.openDevice(driver.device) ?: return false
        port = driver.ports[0].apply {
            open(connection)
            setParameters(1_500_000, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        }

        val ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                reader.feed(data, data.size)
            }
            override fun onRunError(e: Exception) { /* log + eventuale riconnessione */ }
        })
        ioExecutor.submit(ioManager)
        return true
    }*/

    // Una richiesta alla volta (fire-and-wait, chiave fissa pendingResponses[0L]).
    // Ora che le chiamate girano su thread separati (fix ANR), due richieste
    // concorrenti si accavallerebbero sullo stesso slot: la seconda sovrascrive
    // il future della prima, che resta orfano finche' non scatta il timeout -
    // apparendo come "Timeout risposta dall'ESP32" anche se la risposta vera e'
    // arrivata (solo abbinata alla richiesta sbagliata). Il lock forza la vera
    // serializzazione (comunque e' un solo filo seriale fisico, non si perde
    // parallelismo reale) invece di richiedere debounce corretto in ogni singolo
    // punto della UI che chiama l'API.
    private val requestLock = Any()

    // 20s: deve restare comodamente sopra il timeout HTTP dell'ESP32 (15s, vedi
    // main.cpp handleRestRequest) + margine per il giro seriale/radio Skydroid.
    fun sendRequestAndAwait(payload: ByteArray, timeoutMs: Long = 20000): Pair<Int, ByteArray> {
        synchronized(requestLock) {
            val future = CompletableFuture<Pair<Int, ByteArray>>()
            pendingResponses[0L] = future

            val frame = SerialFrame.encode(SerialFrame.REST_SYNC_0, SerialFrame.REST_SYNC_1, payload)
            serialPort?.outputStream?.write(frame)

            return try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                pendingResponses.remove(0L)
                throw IOException("Timeout risposta dall'ESP32", e)
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
        pendingResponses.remove(0L)?.complete(code to body)
    }
}