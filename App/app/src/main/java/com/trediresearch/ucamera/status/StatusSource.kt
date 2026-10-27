package com.trediresearch.ucamera.status

import com.trediresearch.ucamera.webserver.AcquisitionStatus

/**
 * Contratti del sottosistema di stato della camera.
 *
 * Lo stato (acquisizione in corso, numero di foto, profondita') arriva da due sorgenti
 * intercambiabili (pattern Strategy):
 *  - [SocketIoStatusSource]: eventi push di mpe-server, richiede un percorso IP (Wi-Fi);
 *  - [PollingStatusSource]: polling REST, funziona anche sotto sola radio Skydroid
 *    (bridge seriale ESP32), dove Socket.IO non arriva.
 * [StatusCoordinator] tiene attiva la seconda solo quando la prima non e' connessa.
 */

/**
 * Riceve gli aggiornamenti di stato, qualunque sia la sorgente. Le chiamate arrivano da
 * thread di rete: l'implementazione (Window) le riporta sul main thread.
 */
interface StatusListener {

    /** Acquisizione in corso sul dataset [datasetId] con [items] dati salvati. Aggiorna solo il testo di stato. */
    fun onAcquisitionProgress(datasetId: Int, items: Int)

    /**
     * Il server riporta se la camera sta acquisendo. Lo stato Avvia/Ferma resta deciso dagli
     * esiti di start/stop: Window ignora questo valore mentre un comando e' in volo.
     */
    fun onRecordingState(isRecording: Boolean)

    /** Profondita' in metri (altitudine con riferimento BSL, sotto il livello del mare). */
    fun onDepth(meters: Double)
}

/** Una sorgente di stato. [start] e [stop] sono idempotenti. */
interface StatusSource {
    fun start()
    fun stop()
}

/** Sorgente "live" (push) che segnala quando e' connessa, cosi' il polling puo' fermarsi. */
interface LiveStatusSource : StatusSource {
    /** Invocato con true alla connessione e false alla perdita; impostato da [StatusCoordinator]. */
    var connectionListener: ((Boolean) -> Unit)?
}

/**
 * Le chiamate REST usate dal polling: interfaccia minima, implementata da
 * [com.trediresearch.ucamera.webserver.Webserver] e da un fake nei test.
 * Ogni metodo restituisce null se la richiesta fallisce.
 */
interface StatusApi {

    /** GET /datasets/: stato dell'ultimo dataset (elenco completo, usato solo per scoprire un'acquisizione). */
    fun getAcquisitionStatus(): AcquisitionStatus?

    /** GET /datasets/{id}/info?locations=false: stato di un dataset noto, poche centinaia di byte. */
    fun getDatasetAcquisitionStatus(datasetId: Int): AcquisitionStatus?

    /** GET /location_system/status: profondita' in metri, null se non disponibile. */
    fun getDepthMeters(): Double?
}
