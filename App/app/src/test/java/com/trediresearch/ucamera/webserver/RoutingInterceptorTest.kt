package com.trediresearch.ucamera.webserver

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Scelta del collegamento e rinvio via radio. Nessuna rete vera: Wi-Fi e radio sono
 * interceptor finti che rispondono (o falliscono) da soli.
 */
class RoutingInterceptorTest {

    private fun ok(request: Request, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(ResponseBody.create(null, body))
        .build()

    /** Percorso Wi-Fi finto: risponde "wifi", oppure lancia [error] se impostato. */
    private class FakeWifi(private val up: Boolean = true, var error: IOException? = null) : WifiRoute {
        var failures = 0
        val seenHeaders = ArrayList<String?>()
        override fun client(): OkHttpClient? = if (!up) null else OkHttpClient.Builder()
            .addInterceptor { chain ->
                seenHeaders.add(chain.request().header(SerialTransportInterceptor.POLL_TIMEOUT_HEADER))
                error?.let { throw it }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(null, "wifi")).build()
            }.build()
        override fun reportFailure() { failures++ }
    }

    private var radioCalls = 0
    private val radio = Interceptor { chain -> radioCalls++; ok(chain.request(), "radio") }
    private val links = ArrayList<Link>()

    private fun call(wifi: WifiRoute?, method: String = "GET", pollHeader: Boolean = false): String {
        val client = OkHttpClient.Builder()
            .addInterceptor(RoutingInterceptor(wifi, radio) { links.add(it) })
            .build()
        val body = if (method == "GET") null else RequestBody.create(MediaType.parse("application/json"), "{}")
        val builder = Request.Builder().url("http://192.168.1.145:45032/camera/exec").method(method, body)
        if (pollHeader) builder.header(SerialTransportInterceptor.POLL_TIMEOUT_HEADER, "6000")
        return client.newCall(builder.build()).execute().body()!!.string()
    }

    @Test
    fun wifiUpServesTheRequest() {
        assertEquals("wifi", call(FakeWifi()))
        assertEquals(0, radioCalls)
        assertEquals(listOf(Link.WIFI), links)
    }

    @Test
    fun wifiDownGoesStraightToRadio() {
        assertEquals("radio", call(FakeWifi(up = false)))
        assertEquals(listOf(Link.RADIO), links)
    }

    @Test
    fun connectionNeverOpenedIsResentEvenForPost() {
        val wifi = FakeWifi(error = ConnectException("refused"))
        assertEquals("radio", call(wifi, method = "POST"))
        assertEquals(1, wifi.failures)
        assertEquals(listOf(Link.RADIO), links)
    }

    @Test
    fun postLostMidwayIsNotRepeated() {
        val wifi = FakeWifi(error = SocketTimeoutException("timeout"))
        try {
            call(wifi, method = "POST")
            fail("un POST forse gia' eseguito non va rimandato")
        } catch (e: IOException) {
            // atteso
        }
        assertEquals(0, radioCalls)
        assertEquals(1, wifi.failures)
    }

    @Test
    fun putLostMidwayIsRepeatedOnRadio() {
        val wifi = FakeWifi(error = SocketTimeoutException("timeout"))
        assertEquals("radio", call(wifi, method = "PUT"))
        assertEquals(1, wifi.failures)
    }

    @Test
    fun localHeaderNeverReachesTheWire() {
        val wifi = FakeWifi()
        call(wifi, pollHeader = true)
        assertNull(wifi.seenHeaders.single())
    }

    @Test
    fun resendPolicy() {
        assertTrue(RoutingInterceptor.canResendOnRadio("GET", SocketTimeoutException()))
        assertTrue(RoutingInterceptor.canResendOnRadio("put", SocketTimeoutException()))
        assertTrue(RoutingInterceptor.canResendOnRadio("POST", ConnectException()))
        assertFalse(RoutingInterceptor.canResendOnRadio("POST", SocketTimeoutException()))
    }
}
