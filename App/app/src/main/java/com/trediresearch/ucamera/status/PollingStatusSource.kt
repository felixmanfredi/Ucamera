package com.trediresearch.ucamera.status

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Stato della camera via polling REST. E' l'unica sorgente disponibile sotto sola radio
 * Skydroid, dove il bridge seriale trasporta singole richieste HTTP e Socket.IO non arriva;
 * [StatusCoordinator] la attiva solo in quel caso.
 *
 * Per contenere il traffico sul link radio (e la RAM dell'ESP32, che tiene in memoria
 * l'intera risposta) ogni giro fa UNA sola richiesta di stato:
 *  - acquisizione nota   -> GET /datasets/{id}/info?locations=false, ogni [activeIntervalMs];
 *  - nessuna acquisizione -> GET /datasets/ (elenco di tutti i dataset), ogni [idleIntervalMs],
 *                            solo per accorgersi di un'acquisizione avviata da un'altra sessione.
 * La profondita' si chiede al massimo ogni [depthIntervalMs].
 *
 * Prima ogni 5 s partivano GET /datasets/ (~12 KB, cresce con i dataset) e
 * GET /location_system/status, anche con Socket.IO connesso.
 */
class PollingStatusSource(
    private val api: StatusApi,
    private val listener: StatusListener,
    /** Se true il giro viene saltato (es. scatto di prova in volo: ha la precedenza sul bridge). */
    private val isPaused: () -> Boolean = { false },
    private val activeIntervalMs: Long = 5_000L,
    private val idleIntervalMs: Long = 30_000L,
    private val depthIntervalMs: Long = 15_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : StatusSource {

    @Volatile
    private var trackedDatasetId: Int? = null

    // Letto e scritto solo dal thread di polling.
    private var lastDepthAt: Long? = null

    private var executor: ScheduledExecutorService? = null

    /**
     * Dataset dell'acquisizione in corso, noto da start/stop o dalla sincronizzazione
     * iniziale; null = nessuna acquisizione nota (si torna alla scoperta via GET /datasets/).
     */
    fun trackDataset(datasetId: Int?) {
        trackedDatasetId = datasetId
    }

    @Synchronized
    override fun start() {
        if (executor != null) return
        val newExecutor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "status-polling").apply { isDaemon = true }
        }
        executor = newExecutor
        schedule(newExecutor, FIRST_POLL_DELAY_MS)
    }

    @Synchronized
    override fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    /** Pianifica il prossimo giro solo se [owner] e' ancora l'executor attivo (non fermato o sostituito). */
    @Synchronized
    private fun schedule(owner: ScheduledExecutorService, delayMs: Long) {
        if (owner !== executor) return
        owner.schedule({ runCycle(owner) }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun runCycle(owner: ScheduledExecutorService) {
        try {
            pollOnce()
        } catch (e: Exception) {
            // Un giro fallito non deve fermare il polling: si riprova al prossimo.
        }
        schedule(owner, nextDelayMs())
    }

    /** Un giro di polling. Pubblico per i test, in produzione lo chiama solo l'executor. */
    fun pollOnce() {
        if (isPaused()) return
        pollAcquisition()
        pollDepthIfDue()
    }

    /** Attesa prima del prossimo giro: breve durante un'acquisizione, lunga a riposo. */
    fun nextDelayMs(): Long = if (trackedDatasetId != null) activeIntervalMs else idleIntervalMs

    private fun pollAcquisition() {
        val datasetId = trackedDatasetId
        val status = if (datasetId != null) {
            api.getDatasetAcquisitionStatus(datasetId)
        } else {
            api.getAcquisitionStatus()
        } ?: return

        // Se nel frattempo start/stop ha cambiato il dataset seguito, non sovrascriverlo con un dato vecchio.
        if (trackedDatasetId == datasetId) {
            trackedDatasetId = if (status.running) status.datasetId else null
        }
        listener.onRecordingState(status.running)
        if (status.running) {
            listener.onAcquisitionProgress(status.datasetId, status.items)
        }
    }

    private fun pollDepthIfDue() {
        val now = clock()
        val last = lastDepthAt
        if (last != null && now - last < depthIntervalMs) return
        lastDepthAt = now
        api.getDepthMeters()?.let { listener.onDepth(it) }
    }

    private companion object {
        // Breve ritardo iniziale: se Socket.IO si connette subito, il polling si ferma senza aver fatto richieste.
        const val FIRST_POLL_DELAY_MS = 2_000L
    }
}
