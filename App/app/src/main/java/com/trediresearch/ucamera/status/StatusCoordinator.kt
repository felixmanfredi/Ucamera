package com.trediresearch.ucamera.status

/**
 * Sceglie da dove leggere lo stato della camera:
 *  - Socket.IO connesso (percorso IP)  -> solo eventi push, polling fermo;
 *  - Socket.IO non connesso (radio)    -> polling REST attivo.
 *
 * Prima il polling partiva sempre, anche con Socket.IO connesso: in Wi-Fi lo stato
 * arrivava due volte e il polling occupava comunque il bridge seriale/radio.
 */
class StatusCoordinator(
    private val live: LiveStatusSource,
    private val polling: StatusSource,
) {
    private var started = false
    private var liveConnected = false

    init {
        live.connectionListener = { connected -> onLiveConnectionChanged(connected) }
    }

    /** Avvia la sorgente live e, finche' non si connette, il polling. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        liveConnected = false
        live.start()
        applyPolicy()
    }

    /** Ferma entrambe le sorgenti. */
    @Synchronized
    fun stop() {
        started = false
        liveConnected = false
        live.stop()
        polling.stop()
    }

    /** Chiamato dalla sorgente live (thread di Socket.IO) a ogni connessione/disconnessione. */
    @Synchronized
    fun onLiveConnectionChanged(connected: Boolean) {
        liveConnected = connected
        if (started) applyPolicy()
    }

    private fun applyPolicy() {
        if (liveConnected) polling.stop() else polling.start()
    }
}
