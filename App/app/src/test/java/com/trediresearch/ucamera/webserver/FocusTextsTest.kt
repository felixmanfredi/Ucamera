package com.trediresearch.ucamera.webserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** Testi della scheda Fuoco: l'esito si legge dalle impostazioni rilette, non da quelle chieste. */
class FocusTextsTest {

    @Before
    fun fixLocale() {
        Locale.setDefault(Locale.ROOT) // separatore decimale prevedibile
    }

    private fun after(mode: String, lens: Double) = settings(afmode = mode, lensposition = lens)

    @Test
    fun afsSuccessReportsTheLensFound() {
        val o = focusModeOutcome(FOCUS_SINGLE, true, after(FOCUS_SINGLE, 3.1), null)!!
        assertEquals(OutcomeKind.OK, o.kind)
        assertEquals("✔ AF-S riuscito · lente 3.10", o.text)
    }

    @Test
    fun afsFailureSaysWhereTheCameraStayed() {
        val o = focusModeOutcome(FOCUS_SINGLE, false, after(FOCUS_MANUAL, 2.35), "Autofocus non riuscito")!!
        assertEquals(OutcomeKind.ERROR, o.kind)
        assertEquals("✖ AF-S non riuscito · rimasto in Manuale · lente 2.35", o.text)
    }

    @Test
    fun lostReadbackOmitsTheLens() {
        assertEquals("✔ AF-S riuscito", focusModeOutcome(FOCUS_SINGLE, true, null, null)!!.text)
        assertEquals("✖ AF-S non riuscito", focusModeOutcome(FOCUS_SINGLE, false, null, null)!!.text)
    }

    @Test
    fun manualReportsTheFrozenLens() {
        val o = focusModeOutcome(FOCUS_MANUAL, true, after(FOCUS_MANUAL, 2.87), null)!!
        assertEquals(OutcomeKind.INFO, o.kind)
        assertEquals("Manuale · lente bloccata a 2.87", o.text)
    }

    @Test
    fun continuousSuccessNeedsNoExtraText() {
        assertNull(focusModeOutcome(FOCUS_CONTINUOUS, true, after(FOCUS_CONTINUOUS, 1.0), null))
    }

    @Test
    fun otherFailuresShowTheServerMessage() {
        assertEquals("✖ AfSpeed non supportato",
            focusModeOutcome(FOCUS_CONTINUOUS, false, null, "AfSpeed non supportato")!!.text)
        assertEquals("✖ Cambio a Continuo non riuscito",
            focusModeOutcome(FOCUS_CONTINUOUS, false, null, null)!!.text)
    }

    @Test
    fun namesAndSummary() {
        assertEquals("Manuale", focusModeName(FOCUS_MANUAL))
        assertEquals("Singolo", focusModeName(FOCUS_SINGLE))
        assertEquals("Continuo", focusModeName(FOCUS_CONTINUOUS))
        val s = settings(afrange = AF_RANGE_MACRO, afspeed = AF_SPEED_NORMAL, afwindow = afWindowForSize(40))
        assertEquals("MACRO · STD · 40%", afSearchSummary(s))
    }
}
