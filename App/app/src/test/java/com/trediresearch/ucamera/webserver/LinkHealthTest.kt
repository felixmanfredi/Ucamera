package com.trediresearch.ucamera.webserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Isteresi del Wi-Fi: buono si ricontrolla dopo 10 s, guasto si riprova solo dopo 30 s. */
class LinkHealthTest {

    private var now = 0L
    private val health = LinkHealth(upTtlMs = 10_000, downBackoffMs = 30_000, clock = { now })

    @Test
    fun withoutHistoryAProbeIsNeeded() {
        assertTrue(health.needsCheck())
        assertFalse(health.isUp())
    }

    @Test
    fun goodWifiIsTrustedForTheTtl() {
        health.record(true)
        now += 9_999
        assertFalse(health.needsCheck())
        assertTrue(health.isUp())
        now += 1
        assertTrue("scaduto il TTL si ricontrolla", health.needsCheck())
    }

    @Test
    fun failedWifiStaysOnRadioForTheBackoff() {
        health.record(false)
        now += 29_999
        assertFalse("entro i 30 s si resta sulla radio", health.needsCheck())
        assertFalse(health.isUp())
        now += 1
        assertTrue(health.needsCheck())
    }

    @Test
    fun failureDuringGoodPeriodSwitchesToRadioImmediately() {
        health.record(true)
        now += 2_000
        health.record(false) // una richiesta vera e' fallita
        assertFalse(health.isUp())
        assertFalse(health.needsCheck())
    }

    @Test
    fun resetForcesANewProbe() {
        health.record(false)
        health.reset()
        assertTrue(health.needsCheck())
    }
}
