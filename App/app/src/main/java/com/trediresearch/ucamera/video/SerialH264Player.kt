package com.trediresearch.ucamera.video


import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit


/**
 * Collega: lettura seriale USB -> deframing -> depacketizzazione RTP/H264
 * -> decodifica hardware (MediaCodec) -> rendering sulla Surface fornita.
 *
 * Dipendenza: libreria "usb-serial-for-android" (mik3y) per l'accesso alla
 * porta seriale via USB OTG. Aggiungi in build.gradle:
 *   implementation 'com.github.mik3y:usb-serial-for-android:3.7.0'
 *
 * Uso tipico (da un'Activity/Fragment con una SurfaceView pronta):
 *   val player = SerialH264Player(usbSerialPort, surfaceView.holder.surface)
 *   player.start()
 *   ...
 *   player.stop()
 */
class SerialH264Player(
    private val serialPort: SerialPortConnection?,
    private val outputSurface: Surface,
    private val width: Int = 1280,
    private val height: Int = 720
) {
    companion object {
        private const val TAG = "SerialH264Player"
        private const val SERIAL_BAUD = 4_000_000
        private const val READ_TIMEOUT_MS = 200
        private const val READ_BUFFER_SIZE = 4096
    }


    //private lateinit var serialPort: UsbSerialConnection;

    private var decoder: MediaCodec? = null
    private var readerThread: Thread? = null
    @Volatile private var running = false

    private val nalQueue = LinkedBlockingQueue<ByteArray>()

    private val deframer = SerialFrameDeframer { rtpPacket ->
        depacketizer.feed(rtpPacket)
    }
    private val depacketizer = RtpH264Depacketizer { nalUnit ->
        nalQueue.offer(nalUnit)
    }

    private val delegate = object : SerialPortConnection.Delegate {
        override fun connect() {
            Log.d(TAG, "Serial port opened")
        }

        override fun received(param1ArrayOfbyte: ByteArray, param1Int: Int) {
            try {
                if (param1Int > 0) {
                    deframer.feed(param1ArrayOfbyte, param1Int)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore lettura seriale", e)
            }
        }
    }

    fun start() {
        setupSerial()
        setupDecoder()
        running = true

        //readerThread = Thread { readSerialLoop() }.apply { start() }
        Thread { decodeLoop() }.start()
    }

    fun stop() {
        running = false
        serialPort?.removeDelegate(delegate)
        readerThread?.join(1000)
        decoder?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Errore chiusura decoder", e)
            }
        }
        decoder = null
    }

    private fun setupSerial() {

        /*
        serialPort = UsbSerialConnection.newBuilder(App.activity.applicationContext, 1000000)
            .dataBits(8)
            .stopBits(1)
            .parity(UsbSerialPort.PARITY_NONE)
            .build()
        */
        //serialPort = SerialPortConnection.newBuilder("/dev/ttyHS0", 4000000).flags(8192).build()

        serialPort?.addDelegate(delegate)
    }

    private fun setupDecoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, outputSurface, null, 0)
        codec.start()
        decoder = codec
    }

    /*
    private fun readSerialLoop() {
        val buf = ByteArray(READ_BUFFER_SIZE)
        while (running) {
            try {
                val len = serialPort.read(buf, READ_TIMEOUT_MS)
                if (len > 0) {
                    deframer.feed(buf, len)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore lettura seriale", e)
            }
        }
    }
*/
    private fun decodeLoop() {
        val codec = decoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (running) {
            val nalUnit = nalQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue

            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(nalUnit)
                codec.queueInputBuffer(
                    inputIndex, 0, nalUnit.size,
                    System.nanoTime() / 1000, 0
                )
            }

            // Svuota gli output disponibili, renderizzandoli sulla Surface
            // (release(index, true) fa il rendering automatico sulla surface configurata)
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            while (outputIndex >= 0) {
                codec.releaseOutputBuffer(outputIndex, true)
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        }
    }
}
