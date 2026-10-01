package com.trediresearch.ucamera

import com.trediresearch.ucamera.webserver.SerialFrame
import com.trediresearch.ucamera.webserver.SerialFrameReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Verifica del parser sul framing dell'ESP32, che **non e' modificabile**: sync di 2
 * byte, LEN uint16 LE, XOR a 8 bit. Il valore di questi test sta nei casi di falso
 * aggancio, dove il parser precedente perdeva dati in silenzio.
 */
class SerialFrameReaderTest {

    private val r0 = SerialFrame.REST_RESP_SYNC_0
    private val r1 = SerialFrame.REST_RESP_SYNC_1

    private fun restReader(clock: () -> Long = { 0L }): Pair<SerialFrameReader, MutableList<ByteArray>> {
        val out = ArrayList<ByteArray>()
        val reader = SerialFrameReader(r0, r1, 65535, SerialFrame::looksLikeRestResponse, clock) {
            out.add(it)
        }
        return reader to out
    }

    private fun feedAll(reader: SerialFrameReader, wire: ByteArray, chunk: Int = 4096) {
        var off = 0
        while (off < wire.size) {
            val n = minOf(chunk, wire.size - off)
            reader.feed(wire.copyOfRange(off, off + n), n)
            off += n
        }
    }

    private fun restFrame(body: String) =
        SerialFrame.encode(r0, r1, body.toByteArray())

    @Test
    fun `frame singolo`() {
        val (reader, out) = restReader()
        feedAll(reader, restFrame("200\nok"))
        assertEquals(1, out.size)
        assertArrayEquals("200\nok".toByteArray(), out[0])
    }

    /**
     * Rumore che contiene un falso aggancio *breve*: dichiara 5 byte, quindi si completa
     * subito e fallisce il checksum. La scansione deve riprendere da sync + 1 e trovare
     * il frame vero che segue.
     */
    @Test
    fun `rumore prima del frame`() {
        val (reader, out) = restReader()
        val noise = byteArrayOf(0x00, r0, r0, r1, 0x05, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        feedAll(reader, noise + restFrame("200\nok"))
        assertEquals(1, out.size)
        assertArrayEquals("200\nok".toByteArray(), out[0])
    }

    @Test
    fun `feed byte per byte`() {
        val (reader, out) = restReader()
        feedAll(reader, restFrame("200\n" + "x".repeat(5000)), chunk = 1)
        assertEquals(1, out.size)
        assertEquals(4 + 5000, out[0].size)
    }

    @Test
    fun `checksum errato scarta il frame`() {
        val (reader, out) = restReader()
        val f = restFrame("200\ncorpo")
        f[f.size - 1] = (f[f.size - 1].toInt() xor 0xFF).toByte()
        feedAll(reader, f)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `dopo un frame corrotto il successivo passa`() {
        val (reader, out) = restReader()
        val bad = restFrame("200\nrotto")
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0xFF).toByte()
        feedAll(reader, bad + restFrame("201\nbuono"))
        assertEquals(1, out.size)
        assertArrayEquals("201\nbuono".toByteArray(), out[0])
    }

    /**
     * IL CASO CHE PRIMA PERDEVA DATI.
     *
     * Dentro un frame video da decine di KB la coppia `B1 B2` capita per caso. Il parser
     * precedente ci si agganciava, leggeva una lunghezza arbitraria e **consumava quei
     * byte**, inghiottendo la risposta vera che iniziava subito dopo. Qui il falso
     * aggancio dichiara 20000 byte e la risposta vera sta dentro quella finestra: deve
     * essere comunque consegnata.
     */
    @Test
    fun `falso aggancio seguito da un frame valido`() {
        val (reader, out) = restReader()
        val falseSync = byteArrayOf(r0, r1, 0x20, 0x4E) // LEN = 0x4E20 = 20000, plausibile
        val real = restFrame("200\nquesta non deve andare persa")
        // Coda di byte casuali: simula il flusso video che continua ad arrivare e che
        // alla fine smentisce il candidato.
        val tail = Random(42).nextBytes(25000)
        feedAll(reader, falseSync + real + tail)
        assertEquals(1, out.size)
        assertArrayEquals("200\nquesta non deve andare persa".toByteArray(), out[0])
    }

    /**
     * Stesso caso ma senza coda: a flusso fermo il candidato falso non puo' essere
     * smentito dai byte, e senza il timeout bloccherebbe per sempre la risposta vera.
     */
    @Test
    fun `falso aggancio sbloccato dal timeout`() {
        var now = 0L
        val (reader, out) = restReader { now }
        val falseSync = byteArrayOf(r0, r1, 0x20, 0x4E)
        feedAll(reader, falseSync + restFrame("200\nok"))
        assertTrue("prima del timeout resta in attesa", out.isEmpty())

        now = 3000 // oltre CANDIDATE_TIMEOUT_MS
        reader.feed(byteArrayOf(0), 1) // un byte qualunque per rivalutare
        assertEquals(1, out.size)
        assertArrayEquals("200\nok".toByteArray(), out[0])
    }

    /** Due frame consecutivi senza separazione. */
    @Test
    fun `frame consecutivi`() {
        val (reader, out) = restReader()
        feedAll(reader, restFrame("200\nuno") + restFrame("404\ndue"))
        assertEquals(2, out.size)
        assertArrayEquals("200\nuno".toByteArray(), out[0])
        assertArrayEquals("404\ndue".toByteArray(), out[1])
    }

    /** Un corpo binario che contiene la coppia di sync non deve spezzare il frame. */
    @Test
    fun `sync dentro il corpo di un frame valido`() {
        val (reader, out) = restReader()
        val body = ByteArray(3000) { 0 }
        for (at in listOf(100, 900, 2500)) { body[at] = r0; body[at + 1] = r1 }
        val payload = "200\n".toByteArray() + body
        feedAll(reader, SerialFrame.encode(r0, r1, payload))
        assertEquals(1, out.size)
        assertArrayEquals(payload, out[0])
    }

    /** La corsia video non ha filtro di plausibilita': payload binario arbitrario. */
    @Test
    fun `corsia video con payload binario`() {
        val out = ArrayList<ByteArray>()
        val reader = SerialFrameReader(
            SerialFrame.FPV_SYNC_0, SerialFrame.FPV_SYNC_1, 40000, null, { 0L }
        ) { out.add(it) }
        val img = Random(7).nextBytes(30000)
        feedAll(reader, SerialFrame.encode(SerialFrame.FPV_SYNC_0, SerialFrame.FPV_SYNC_1, img))
        assertEquals(1, out.size)
        assertArrayEquals(img, out[0])
    }

    /** I frame dell'altra corsia non devono essere raccolti da questo lettore. */
    @Test
    fun `corsia diversa ignorata`() {
        val (reader, out) = restReader()
        val fpv = SerialFrame.encode(
            SerialFrame.FPV_SYNC_0, SerialFrame.FPV_SYNC_1, Random(3).nextBytes(5000)
        )
        feedAll(reader, fpv + restFrame("200\nmio"))
        assertEquals(1, out.size)
        assertArrayEquals("200\nmio".toByteArray(), out[0])
    }

    @Test
    fun `filtro di plausibilita della risposta`() {
        fun ok(s: String) = SerialFrame.looksLikeRestResponse(s.toByteArray(), 0, s.length)
        assertTrue(ok("200\n"))
        assertTrue(ok("200\n{\"status\":\"success\"}"))
        assertTrue(ok("-1\nERRORE_HTTPCLIENT: timeout"))
        assertTrue(ok("502\n"))
        assertFalse(ok("ÿ« binario"))
        assertFalse(ok("abc\n"))
        assertFalse(ok("2"))
        assertFalse(ok("20000000\n")) // troppe cifre per un codice HTTP
    }
}
