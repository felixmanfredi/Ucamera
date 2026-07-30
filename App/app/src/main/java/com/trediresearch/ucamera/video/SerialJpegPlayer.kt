package com.trediresearch.ucamera.video

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import android.view.Surface

/**
 * FPV receiver for when the JPEG/UDP stream (jpeg_udp_streamer.py) reaches
 * the phone relayed over the UART bridge instead of directly over WiFi/UDP:
 * the ESP32 firmware (SkydroidSerial2Net) joins the FPV multicast group over
 * WiFi, reassembles each frame's UDP chunks in its own RAM (handleFpvChunk()
 * in main.cpp), and writes the complete JPEG as a single 0xAA/0x55-framed
 * serial packet - one full image per deframed payload here, no further
 * chunk/frame-id bookkeeping needed on this side.
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
    }

    private val deframer = SerialFrameDeframer { jpeg ->
        renderFrame(jpeg)
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
        serialPort?.addDelegate(delegate)
    }

    fun stop() {
        // SerialPortConnection e' owned/opened/chiuso da chi l'ha costruito (Window);
        // qui basta smettere di ascoltare, cosi' non resta un delegate orfano registrato
        // (es. quando si passa da FpvPreviewMode.JPEG_SERIAL a SERIAL_H264 o viceversa).
        serialPort?.removeDelegate(delegate)
    }

    private fun renderFrame(jpeg: ByteArray) {
        if (!outputSurface.isValid) return
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        try {
            val canvas = outputSurface.lockCanvas(null) ?: return
            try {
                canvas.drawBitmap(bitmap, null, Rect(0, 0, canvas.width, canvas.height), null)
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
