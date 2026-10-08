package com.trediresearch.ucamera

import com.google.gson.Gson
import com.trediresearch.ucamera.webserver.AF_RANGES
import com.trediresearch.ucamera.webserver.AF_RANGE_FULL
import com.trediresearch.ucamera.webserver.AF_RANGE_MACRO
import com.trediresearch.ucamera.webserver.AF_RANGE_NORMAL
import com.trediresearch.ucamera.webserver.AF_SPEEDS
import com.trediresearch.ucamera.webserver.AF_SPEED_FAST
import com.trediresearch.ucamera.webserver.AF_SPEED_NORMAL
import com.trediresearch.ucamera.webserver.AF_WINDOW_CENTER
import com.trediresearch.ucamera.webserver.ExecCommandRequest
import com.trediresearch.ucamera.webserver.afRangeLabel
import com.trediresearch.ucamera.webserver.afSpeedLabel
import com.trediresearch.ucamera.webserver.afWindowLabel
import com.trediresearch.ucamera.webserver.settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parametri di ricerca del fuoco (afrange / afspeed / afwindow).
 *
 * Il test che conta davvero e' il primo gruppo: i tre campi sono NULLABILI apposta,
 * perche' l'app fa PUT dell'intero oggetto settings a ogni tap sui +/- e un device con
 * il plugin vecchio non conosce quelle chiavi. Se finissero nel JSON con un default
 * locale, ogni singolo tocco su luminosita' o contrasto si porterebbe dietro un
 * "Unsupported changes to afrange property" dal server.
 */
class FocusSettingsTest {

    private val gson = Gson()

    // --- serializzazione: cio' che finisce (o non finisce) nel PUT -----------------

    @Test
    fun `i campi di fuoco non arrivati dal server non vengono spediti`() {
        val json = gson.toJson(settings())   // nessun GET: restano null
        assertFalse("afrange non deve comparire nel PUT", json.contains("afrange"))
        assertFalse("afspeed non deve comparire nel PUT", json.contains("afspeed"))
        assertFalse("afwindow non deve comparire nel PUT", json.contains("afwindow"))
        // Gli altri parametri invece devono esserci sempre.
        assertTrue(json.contains("brightness"))
        assertTrue(json.contains("afmode"))
    }

    @Test
    fun `i campi letti dal server vengono rispediti tali e quali`() {
        val fromServer = """
            {"brightness":0.0,"afmode":"afc","afrange":"full","afspeed":"fast",
             "afwindow":[0.3,0.3,0.4,0.4],"aeenable":false}
        """.trimIndent()
        val parsed = gson.fromJson(fromServer, settings::class.java)
        assertEquals(AF_RANGE_FULL, parsed.afrange)
        assertEquals(AF_SPEED_FAST, parsed.afspeed)
        assertEquals(listOf(0.3, 0.3, 0.4, 0.4), parsed.afwindow)

        val json = gson.toJson(parsed)
        assertTrue(json.contains("\"afrange\":\"full\""))
        assertTrue(json.contains("\"afspeed\":\"fast\""))
        assertTrue(json.contains("\"afwindow\":[0.3,0.3,0.4,0.4]"))
    }

    @Test
    fun `finestra vuota e finestra assente sono stati diversi`() {
        // [] = fotogramma intero, valore legittimo che il device ha mandato.
        val empty = gson.fromJson("""{"afwindow":[]}""", settings::class.java)
        assertEquals(emptyList<Double>(), empty.afwindow)
        assertTrue(gson.toJson(empty).contains("\"afwindow\":[]"))

        // assente = il device non conosce il parametro: non va spedito.
        val missing = gson.fromJson("""{"brightness":0.0}""", settings::class.java)
        assertEquals(null, missing.afwindow)
        assertFalse(gson.toJson(missing).contains("afwindow"))
    }

    @Test
    fun `exec invia la finestra come array di numeri, non come stringa`() {
        val req = ExecCommandRequest("focus_mode",
            mapOf("mode" to "afc", "window" to AF_WINDOW_CENTER))
        val json = gson.toJson(req)
        assertTrue(json, json.contains("\"window\":[0.3,0.3,0.4,0.4]"))
        assertTrue(json, json.contains("\"mode\":\"afc\""))
    }

    @Test
    fun `exec senza parametri resta identico a prima`() {
        // Il plugin vecchio deve continuare a ricevere esattamente la vecchia richiesta.
        assertEquals("""{"cmd":"autofocus"}""", gson.toJson(ExecCommandRequest("autofocus")))
    }

    // --- vocabolario: deve restare allineato al plugin -----------------------------

    @Test
    fun `i valori ammessi sono quelli del plugin`() {
        // Allineati a _AF_RANGES / _AF_SPEEDS in arducam.py: il server RIFIUTA tutto il
        // resto con un errore, non lo corregge in silenzio.
        assertEquals(listOf("normal", "macro", "full"), AF_RANGES)
        assertEquals(listOf("normal", "fast"), AF_SPEEDS)
        assertEquals(4, AF_WINDOW_CENTER.size)
        // La finestra centrale deve stare dentro il fotogramma, altrimenti il plugin la
        // rifiuta: x+w e y+h non possono superare 1.
        val (x, y, w, h) = AF_WINDOW_CENTER
        assertTrue(x >= 0.0 && y >= 0.0 && w > 0.0 && h > 0.0)
        assertTrue("x+w fuori dal fotogramma", x + w <= 1.0)
        assertTrue("y+h fuori dal fotogramma", y + h <= 1.0)
    }

    // --- etichette ----------------------------------------------------------------

    @Test
    fun `le etichette coprono ogni valore ammesso e il caso non supportato`() {
        assertEquals("NORM", afRangeLabel(AF_RANGE_NORMAL))
        assertEquals("MACRO", afRangeLabel(AF_RANGE_MACRO))
        assertEquals("FULL", afRangeLabel(AF_RANGE_FULL))
        assertEquals("STD", afSpeedLabel(AF_SPEED_NORMAL))
        assertEquals("RAPIDA", afSpeedLabel(AF_SPEED_FAST))
        assertEquals("PIENO", afWindowLabel(emptyList()))
        assertEquals("CENTRO", afWindowLabel(AF_WINDOW_CENTER))

        // null = device che non espone il parametro: nessuna etichetta inventata.
        assertEquals("--", afRangeLabel(null))
        assertEquals("--", afSpeedLabel(null))
        assertEquals("--", afWindowLabel(null))
        // valore sconosciuto dal server: stesso trattamento, non si indovina.
        assertEquals("--", afRangeLabel("vicinissimo"))
        assertEquals("--", afSpeedLabel("turbo"))
    }

    @Test
    fun `ogni valore ammesso ha una etichetta distinta`() {
        assertEquals(AF_RANGES.size, AF_RANGES.map { afRangeLabel(it) }.toSet().size)
        assertEquals(AF_SPEEDS.size, AF_SPEEDS.map { afSpeedLabel(it) }.toSet().size)
    }
}
