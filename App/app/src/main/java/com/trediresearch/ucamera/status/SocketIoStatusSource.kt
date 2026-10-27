package com.trediresearch.ucamera.status

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import org.json.JSONObject

/**
 * Stato della camera dagli eventi Socket.IO di mpe-server. Richiede un percorso IP (Wi-Fi):
 * sotto sola radio non si connette e [StatusCoordinator] attiva il polling REST.
 *
 * Sostituisce SocketIOConnection e i gestori che stavano in Window, correggendone i bug:
 *  - device_status: si filtra su device_type == "camera". Il server invia name
 *    "arducam.Arducam", quindi il vecchio confronto con "arducam" non riusciva mai e lo
 *    stato di acquisizione non veniva aggiornato;
 *  - disconnessione: niente Thread.sleep() sul thread degli eventi e nessun nuovo socket
 *    creato senza listener ne' connect(). La riconnessione automatica del client basta;
 *  - la perdita di Socket.IO non segna piu' la camera come offline: puo' essere
 *    raggiungibile via radio, e il polling subentra;
 *  - datasets_storage_status: il progresso si riporta solo per un'acquisizione non
 *    completata (prima mostrava anche l'ultima conclusa, sovrascrivendo "Ready").
 */
class SocketIoStatusSource(
    private val url: String,
    private val listener: StatusListener,
) : LiveStatusSource {

    @Volatile
    override var connectionListener: ((Boolean) -> Unit)? = null

    private var socket: Socket? = null

    @Synchronized
    override fun start() {
        if (socket != null) return
        val options = IO.Options().apply {
            transports = arrayOf(WebSocket.NAME)
            path = "/socket"
        }
        val newSocket = try {
            IO.socket(url, options)
        } catch (e: Exception) {
            Log.e(TAG, "URL Socket.IO non valido: $url", e)
            return
        }
        newSocket.on(Socket.EVENT_CONNECT) { connectionListener?.invoke(true) }
        newSocket.on(Socket.EVENT_DISCONNECT) { connectionListener?.invoke(false) }
        newSocket.on(Socket.EVENT_CONNECT_ERROR) { connectionListener?.invoke(false) }
        newSocket.on("device_status") { args -> forEachObject(args, ::onDeviceStatus) }
        newSocket.on("datasets_storage_status") { args -> forEachObject(args, ::onDatasetsStorageStatus) }
        newSocket.on("location_status") { args -> forEachObject(args, ::onLocationStatus) }
        socket = newSocket
        newSocket.connect()
    }

    @Synchronized
    override fun stop() {
        val current = socket ?: return
        socket = null
        current.off() // nessun callback dopo lo stop
        current.disconnect()
    }

    private fun onDeviceStatus(device: JSONObject) {
        if (device.optString("device_type") != "camera" || !device.has("is_recording")) return
        listener.onRecordingState(device.getBoolean("is_recording"))
    }

    private fun onDatasetsStorageStatus(storage: JSONObject) {
        val acquisition = storage.optJSONObject("current_camera_acquisition") ?: return
        if (acquisition.length() == 0 || acquisition.optBoolean("is_completed", false)) return
        listener.onAcquisitionProgress(acquisition.getInt("dataset_id"), acquisition.optInt("items", 0))
    }

    private fun onLocationStatus(location: JSONObject) {
        // altitude e' una tupla Python serializzata come array: [valore, "BSL"|"ASL"]
        val altitude = location.optJSONArray("altitude") ?: return
        if (altitude.length() >= 2 && altitude.optString(1) == "BSL") {
            listener.onDepth(altitude.getDouble(0))
        }
    }

    /** Applica [handler] a ogni argomento JSONObject dell'evento; un evento malformato non deve rompere il socket. */
    private fun forEachObject(args: Array<out Any?>, handler: (JSONObject) -> Unit) {
        for (arg in args) {
            if (arg !is JSONObject) continue
            try {
                handler(arg)
            } catch (e: Exception) {
                Log.w(TAG, "Evento Socket.IO non valido: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "UCamera"
    }
}
