package com.trediresearch.ucamera.video

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import android.view.Surface
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Low-latency FPV receiver for the JPEG-over-UDP stream produced by
 * streaming/jpeg_udp_streamer.py (Arducam side). No deframing needed like
 * SerialH264Player/SerialFrameDeframer: UDP already delivers one complete
 * datagram per receive() call, so there is no byte stream to re-frame.
 *
 * Wire format per datagram (must match jpeg_udp_streamer.py's HEADER_FORMAT):
 *   frame_id     uint32  big-endian  wraps at 2^32
 *   chunk_index  uint16  big-endian  0-based index of this chunk in the frame
 *   chunk_count  uint16  big-endian  total chunks composing the frame
 *   payload      remaining bytes     JPEG chunk
 *
 * A frame is considered ready once `chunk_count` distinct chunk_index values
 * have been collected for the current frame_id; an incomplete frame is
 * dropped as soon as a datagram for a newer frame_id arrives (favor low
 * latency over never losing a frame).
 *
 * Usage (mirrors SerialH264Player):
 *   val player = JpegUdpPlayer(port = 5600, outputSurface = surfaceView.holder.surface)
 *   player.start()
 *   ...
 *   player.stop()
 *
 * Pass `multicastGroup`/`context` only if the sender is configured with a
 * multicast broadcast_addr (224.0.0.0-239.255.255.255); plain UDP broadcast
 * or unicast needs neither (requires android.permission.CHANGE_WIFI_MULTICAST_STATE
 * in AndroidManifest.xml when multicastGroup is used).
 */
class JpegUdpPlayer(
    private val port: Int,
    private val outputSurface: Surface,
    private val multicastGroup: String? = null,
    private val context: Context? = null
) {
    companion object {
        private const val TAG = "JpegUdpPlayer"
        private const val HEADER_SIZE = 8
        private const val MAX_DATAGRAM_SIZE = 2048
        private const val SOCKET_TIMEOUT_MS = 500
    }

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var receiverThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var currentFrameId: Long = -1
    private var expectedChunkCount = 0
    private val chunks = HashMap<Int, ByteArray>()

    fun start() {
        if (running) return
        running = true
        receiverThread = Thread { receiveLoop() }.apply { start() }
    }

    fun stop() {
        running = false
        receiverThread?.join(1000)
        receiverThread = null
        closeSocket()
    }

    private fun openSocket(): DatagramSocket {
        val group = multicastGroup
        if (group != null) {
            acquireMulticastLock()
            val ms = MulticastSocket(port)
            ms.joinGroup(InetAddress.getByName(group))
            return ms
        }
        return DatagramSocket(port)
    }

    private fun closeSocket() {
        try {
            val sock = socket
            if (sock is MulticastSocket && multicastGroup != null) {
                sock.leaveGroup(InetAddress.getByName(multicastGroup))
            }
            sock?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing UDP socket", e)
        }
        socket = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        val wifiManager = context?.applicationContext
            ?.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val lock = wifiManager.createMulticastLock(TAG)
        lock.setReferenceCounted(true)
        lock.acquire()
        multicastLock = lock
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing multicast lock", e)
        }
        multicastLock = null
    }

    private fun receiveLoop() {
        val sock = try {
            openSocket()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open UDP socket on port $port", e)
            running = false
            return
        }
        socket = sock
        sock.soTimeout = SOCKET_TIMEOUT_MS

        val buffer = ByteArray(MAX_DATAGRAM_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)

        while (running) {
            try {
                sock.receive(packet)
                handlePacket(buffer, packet.length)
            } catch (e: SocketTimeoutException) {
                // No data within timeout; loop back and check `running`.
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Error receiving UDP packet", e)
            }
        }
    }

    private fun handlePacket(buffer: ByteArray, length: Int) {
        if (length < HEADER_SIZE) return

        val header = ByteBuffer.wrap(buffer, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        val frameId = header.int.toLong() and 0xFFFFFFFFL
        val chunkIndex = header.short.toInt() and 0xFFFF
        val chunkCount = header.short.toInt() and 0xFFFF
        if (chunkCount == 0 || chunkIndex >= chunkCount) return

        if (frameId != currentFrameId) {
            currentFrameId = frameId
            expectedChunkCount = chunkCount
            chunks.clear()
        }

        chunks[chunkIndex] = buffer.copyOfRange(HEADER_SIZE, length)

        if (chunks.size == expectedChunkCount) {
            val jpeg = reassembleJpeg()
            chunks.clear()
            currentFrameId = -1
            if (jpeg != null) renderFrame(jpeg)
        }
    }

    private fun reassembleJpeg(): ByteArray? {
        val totalSize = chunks.values.sumOf { it.size }
        val out = ByteArray(totalSize)
        var offset = 0
        for (i in 0 until expectedChunkCount) {
            val chunk = chunks[i] ?: return null
            System.arraycopy(chunk, 0, out, offset, chunk.size)
            offset += chunk.size
        }
        return out
    }

    private fun renderFrame(jpeg: ByteArray) {
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
