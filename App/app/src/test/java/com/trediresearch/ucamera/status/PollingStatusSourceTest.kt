package com.trediresearch.ucamera.status

import com.trediresearch.ucamera.webserver.AcquisitionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stato dell'acquisizione con ritmo 5 s / 30 s, profondita' ogni 2 s sia a riposo sia in
 * acquisizione. Ogni giro fa solo le richieste dovute.
 */
class PollingStatusSourceTest {

    private class FakeApi : StatusApi {
        val calls = ArrayList<String>()
        var latest: AcquisitionStatus? = AcquisitionStatus(false, 0, 0)
        val byId = HashMap<Int, AcquisitionStatus?>()
        var depth: Double? = 3.25

        override fun getAcquisitionStatus(): AcquisitionStatus? { calls.add("datasets"); return latest }
        override fun getDatasetAcquisitionStatus(datasetId: Int): AcquisitionStatus? {
            calls.add("info:$datasetId"); return byId[datasetId]
        }
        override fun getDepthMeters(): Double? { calls.add("depth"); return depth }
    }

    private class RecordingListener : StatusListener {
        val events = ArrayList<String>()
        override fun onAcquisitionProgress(datasetId: Int, items: Int) { events.add("progress:$datasetId:$items") }
        override fun onRecordingState(isRecording: Boolean) { events.add("recording:$isRecording") }
        override fun onDepth(meters: Double) { events.add("depth:$meters") }
    }

    private var now = 0L

    private fun source(api: StatusApi, listener: StatusListener, paused: () -> Boolean = { false }) =
        PollingStatusSource(api, listener, paused, activeIntervalMs = 5_000, idleIntervalMs = 30_000,
            depthIntervalMs = 2_000, clock = { now })

    /** Esegue i giri come l'executor: avanza l'orologio di nextDelayMs() fino a [untilMs]. */
    private fun runUntil(polling: PollingStatusSource, untilMs: Long) {
        while (true) {
            val next = now + polling.nextDelayMs()
            if (next > untilMs) return
            now = next
            polling.pollOnce()
        }
    }

    @Test
    fun withoutTrackedDatasetDiscoversWithDatasetList() {
        val api = FakeApi().apply { latest = AcquisitionStatus(true, 27, 10) }
        val listener = RecordingListener()
        val polling = source(api, listener)

        polling.pollOnce()

        assertEquals(listOf("datasets", "depth"), api.calls)
        assertEquals(listOf("recording:true", "progress:27:10", "depth:3.25"), listener.events)
        assertEquals("il prossimo giro e' la profondita', fra 2 s", 2_000L, polling.nextDelayMs())

        api.calls.clear()
        api.byId[27] = AcquisitionStatus(true, 27, 11)
        runUntil(polling, 5_000)
        assertEquals("acquisizione scoperta: poi solo la rotta leggera /info, ogni 5 s",
            listOf("depth", "depth", "info:27"), api.calls)
    }

    @Test
    fun depthEveryTwoSecondsAlsoWhenIdle() {
        val api = FakeApi()
        val polling = source(api, RecordingListener())
        polling.pollOnce()
        api.calls.clear()

        runUntil(polling, 30_000)

        assertEquals("15 profondita' in 30 s", 15, api.calls.count { it == "depth" })
        assertEquals("a riposo lo stato resta ogni 30 s", 1, api.calls.count { it == "datasets" })
    }

    @Test
    fun depthRateIsTheSameDuringAcquisition() {
        val api = FakeApi().apply { byId[5] = AcquisitionStatus(true, 5, 1) }
        val polling = source(api, RecordingListener())
        polling.trackDataset(5)
        polling.pollOnce()
        api.calls.clear()

        runUntil(polling, 10_000)

        assertEquals(5, api.calls.count { it == "depth" })
        assertEquals(2, api.calls.count { it == "info:5" })
    }

    @Test
    fun trackedDatasetUsesInfoRouteAndStopsWhenCompleted() {
        val api = FakeApi().apply { byId[5] = AcquisitionStatus(false, 5, 40) }
        val listener = RecordingListener()
        val polling = source(api, listener)
        polling.trackDataset(5)

        polling.pollOnce()

        assertEquals(listOf("info:5", "depth"), api.calls)
        assertTrue(listener.events.contains("recording:false"))
        assertTrue("nessun progresso per un dataset completato", listener.events.none { it.startsWith("progress") })

        api.calls.clear()
        runUntil(polling, 29_999)
        assertTrue("acquisizione conclusa: lo stato torna al ritmo di riposo",
            api.calls.none { it == "datasets" || it.startsWith("info") })
    }

    @Test
    fun failedRequestKeepsTrackedDataset() {
        val api = FakeApi().apply { byId[9] = null }
        val listener = RecordingListener()
        val polling = source(api, listener)
        polling.trackDataset(9)

        polling.pollOnce()

        assertTrue("una richiesta fallita non deve cambiare lo stato", listener.events.none { it.startsWith("recording") })
        api.calls.clear()
        runUntil(polling, 5_000)
        assertTrue("si continua a seguire il dataset", api.calls.contains("info:9"))
    }

    @Test
    fun pausedCycleMakesNoRequestsAndRetriesSoon() {
        val api = FakeApi()
        val polling = source(api, RecordingListener(), paused = { true })
        polling.pollOnce()
        assertTrue(api.calls.isEmpty())
        assertTrue("in pausa si riprova presto, senza girare a vuoto", polling.nextDelayMs() in 1..2_000)
    }
}
