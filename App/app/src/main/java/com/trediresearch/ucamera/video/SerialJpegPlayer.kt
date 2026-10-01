package com.trediresearch.ucamera.video

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import android.view.Surface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * FPV receiver for when the JPEG/UDP stream (jpeg_udp_streamer.py) reaches
 * the phone relayed over the UART bridge instead of directly over WiFi/UDP:
 * the ESP32 firmware (SkydroidSerial2Net) joins the FPV multicast group over
 * WiFi, reassembles each frame's UDP chunks in its own RAM (handleFpvChunk()
 * in main.cpp), and writes the complete image as a single 0xAA/0x55-framed
 * serial packet - one full image per deframed payload here, no further
 * chunk/frame-id bookkeeping needed on this side.
 *
 * Nota sul formato: il plugin puo' essere configurato in JPEG o WebP
 * (fpv_streamer.image_format nel manifest, oggi "webp" a qualita' 50).
 * BitmapFactory riconosce il formato dai byte, quindi qui non cambia nulla.
 *
 * Usage (mirrors SerialH264Player):
 *   val player = SerialJpegPlayer(serialPort, surfaceView.holder.surface)
 *   player.start()
 *   ...
 *   player.stop()
 */
class SerialJpegPlayer(
    private val serialPort: SerialPortConnection?,
    private val outputSurface: Surface
) {
    companion object {
        private const val TAG = "SerialJpegPlayer"
        private const val RENDER_POLL_MS = 200L
    }

    // Coda a uno slot con politica "scarta il vecchio": la decodifica non deve mai
    // rallentare la lettura della seriale, e per una preview un frame in ritardo non
    // serve a nulla - meglio perderlo e mostrare l'ultimo arrivato.
    private val pending = ArrayBlockingQueue<ByteArray>(1)

    @Volatile
    private var running = false
    private var renderThread: Thread? = null

    private val deframer = SerialFrameDeframer { jpeg ->
        // SOLO accodamento: siamo sul ReadThread di SerialPortConnection, cioe' l'unico
        // thread che drena /dev/ttyHS0. Prima la decodifica e il lockCanvas (che blocca
        // sul vsync) giravano proprio qui: a 4 Mbaud il ring RX da 2048 byte si riempie
        // in pochi millisecondi, quindi ogni stallo di rendering faceva perdere byte -
        // frame video troncati e, soprattutto, risposte REST corrotte sulla stessa UART.
        if (!pending.offer(jpeg)) {
            pending.poll()
            pending.offer(jpeg)
        }
    }

    private val delegate = object : SerialPortConnection.Delegate {
        override fun connect() {
            Log.d(TAG, "Serial port opened")
        }

        override fun received(data: ByteArray, len: Int) {
            try {
                if (len > 0) deframer.feed(data, len)
            } catch (e: Exception) {
                Log.e(TAG, "Errore lettura seriale", e)
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        renderThread = Thread({ renderLoop() }, "SerialJpegPlayer-render").apply {
            isDaemon = true
            start()
        }
        serialPort?.addDelegate(delegate)
    }

    fun stop() {
        // SerialPortConnection e' owned/opened/chiuso da chi l'ha costruito (Window);
        // qui basta smettere di ascoltare, cosi' non resta un delegate orfano registrato
        // (es. quando si passa da FpvPreviewMode.JPEG_SERIAL a SERIAL_H264 o viceversa).
        serialPort?.removeDelegate(delegate)
        running = false
        renderThread?.interrupt()
        renderThread = null
        pending.clear()
    }

    private fun renderLoop() {
        while (running) {
            val jpeg = try {
                pending.poll(RENDER_POLL_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                return
            } ?: continue
            renderFrame(jpeg)
        }
    }

    /** Rettangolo massimo con le proporzioni della sorgente, centrato nella destinazione. */
    private fun fitCenter(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        if (srcW <= 0 || srcH <= 0) return Rect(0, 0, dstW, dstH)
        val scale = minOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        val w = (srcW * scale).toInt()
        val h = (srcH * scale).toInt()
        val left = (dstW - w) / 2
        val top = (dstH - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    private fun renderFrame(jpeg: ByteArray) {
        if (!outputSurface.isValid) return
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        if (bitmap == null) {
            // Un frame troncato produceva un return silenzioso: senza questo log non
            // c'e' modo di distinguere "nessun frame in arrivo" da "frame corrotti".
            Log.w(TAG, "Frame non decodificabile (${jpeg.size} byte)")
            return
        }
        try {
            val canvas = outputSurface.lockCanvas(null) ?: return
            try {
                // Letterbox invece di riempire tutto: la sorgente e' 4:3 (QVGA) mentre
                // la TextureView cambia proporzioni fra finestra compatta e tutto
                // schermo. Disegnando sul rettangolo pieno l'immagine risultava stirata
                // proprio nella modalita' in cui serve guardarla bene.
                canvas.drawColor(android.graphics.Color.BLACK)
                canvas.drawBitmap(bitmap, null, fitCenter(bitmap.width, bitmap.height,
                    canvas.width, canvas.height), null)
            } finally {
                outputSurface.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering frame", e)
        } finally {
            bitmap.recycle()
        }
    }
}
