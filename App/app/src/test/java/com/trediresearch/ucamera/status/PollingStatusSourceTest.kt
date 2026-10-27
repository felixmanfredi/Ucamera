package com.trediresearch.ucamera.status

import com.trediresearch.ucamera.webserver.AcquisitionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Una sola richiesta di stato per giro, scelta in base all'acquisizione seguita. */
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
            depthIntervalMs = 15_000, clock = { now })

    @Test
    fun withoutTrackedDatasetDiscoversWithDatasetList() {
        val api = FakeApi().apply { latest = AcquisitionStatus(true, 27, 10) }
        val listener = RecordingListener()
        val polling = source(api, listener)

        polling.pollOnce()

        assertEquals(listOf("datasets", "depth"), api.calls)
        assertEquals(listOf("recording:true", "progress:27:10", "depth:3.25"), listener.events)
        assertEquals("acquisizione scoperta: si passa al ritmo attivo", 5_000L, polling.nextDelayMs())

        api.calls.clear()
        api.byId[27] = AcquisitionStatus(true, 27, 11)
        now += 5_000
        polling.pollOnce()
        assertEquals("poi solo la rotta leggera /info, profondita' non ancora dovuta", listOf("info:27"), api.calls)
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
        assertEquals("acquisizione conclusa: ritmo a riposo", 30_000L, polling.nextDelayMs())
    }

    @Test
    fun failedRequestKeepsTrackedDataset() {
        val api = FakeApi().apply { byId[9] = null }
        val listener = RecordingListener()
        val polling = source(api, listener)
        polling.trackDataset(9)

        polling.pollOnce()

        assertTrue("una richiesta fallita non deve cambiare lo stato", listener.events.none { it.startsWith("recording") })
        assertEquals(5_000L, polling.nextDelayMs())
    }

    @Test
    fun pausedCycleMakesNoRequests() {
        val api = FakeApi()
        val polling = source(api, RecordingListener(), paused = { true })
        polling.pollOnce()
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun depthIsRateLimited() {
        val api = FakeApi()
        val polling = source(api, RecordingListener())
        polling.pollOnce()
        now += 10_000
        polling.pollOnce()
        now += 5_000
        polling.pollOnce()
        assertEquals(2, api.calls.count { it == "depth" })
    }
}
