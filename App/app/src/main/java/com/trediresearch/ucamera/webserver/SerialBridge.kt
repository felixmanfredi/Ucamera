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
    private val pendingResponses = ConcurrentHashMap<Long, CompletableFuture<Pair<Int, String>>>()
    private val reader = SerialFrameReader { sync0, sync1, payload ->
        if (sync0 == SerialFrame.REST_RESP_SYNC_0 && sync1 == SerialFrame.REST_RESP_SYNC_1) {
            handleResponseFrame(payload)
        }
    }

    fun connect(): Boolean{
        serialPort?.setDelegate(object : SerialPortConnection.Delegate {

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
        })

        return true;
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

    // Nota: qui usiamo una richiesta alla volta (fire-and-wait).
    // Se ti serve concorrenza, aggiungi un requestId nel payload REST
    // e fai il match nella risposta invece di assumere serializzazione.
    fun sendRequestAndAwait(payload: ByteArray, timeoutMs: Long = 8000): Pair<Int, String> {
        val future = CompletableFuture<Pair<Int, String>>()
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

    private fun handleResponseFrame(payload: ByteArray) {
        val text = String(payload, Charsets.UTF_8)
        val idx = text.indexOf('\n')
        if (idx < 0) return
        val code = text.substring(0, idx).trim().toIntOrNull() ?: -1
        val body = text.substring(idx + 1)
        pendingResponses.remove(0L)?.complete(code to body)
    }
}