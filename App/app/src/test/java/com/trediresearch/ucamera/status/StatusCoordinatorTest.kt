package com.trediresearch.ucamera.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il polling deve girare solo quando la sorgente live (Socket.IO) non e' connessa. */
class StatusCoordinatorTest {

    private class FakeSource : StatusSource {
        var running = false
        var starts = 0
        override fun start() { running = true; starts++ }
        override fun stop() { running = false }
    }

    private class FakeLive : LiveStatusSource {
        override var connectionListener: ((Boolean) -> Unit)? = null
        var running = false
        override fun start() { running = true }
        override fun stop() { running = false }
        fun connect(connected: Boolean) = connectionListener?.invoke(connected)
    }

    @Test
    fun pollingRunsUntilLiveConnects() {
        val live = FakeLive()
        val polling = FakeSource()
        val coordinator = StatusCoordinator(live, polling)

        coordinator.start()
        assertTrue(live.running)
        assertTrue("senza Socket.IO serve il polling", polling.running)

        live.connect(true)
        assertFalse("con Socket.IO connesso il polling si ferma", polling.running)

        live.connect(false)
        assertTrue("persa la connessione, il polling riparte", polling.running)
    }

    @Test
    fun stopHaltsBothAndIgnoresLateCallbacks() {
        val live = FakeLive()
        val polling = FakeSource()
        val coordinator = StatusCoordinator(live, polling)
        coordinator.start()

        coordinator.stop()
        assertFalse(live.running)
        assertFalse(polling.running)

        live.connect(false) // callback tardivo dopo lo stop
        assertFalse("dopo stop() il polling non deve ripartire", polling.running)
    }

    @Test
    fun startIsIdempotent() {
        val live = FakeLive()
        val polling = FakeSource()
        val coordinator = StatusCoordinator(live, polling)
        coordinator.start()
        coordinator.start()
        assertEquals(1, polling.starts)
    }
}
