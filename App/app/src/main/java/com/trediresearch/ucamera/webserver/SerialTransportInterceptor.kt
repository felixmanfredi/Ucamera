package com.trediresearch.ucamera.webserver

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody

class SerialTransportInterceptor(private val bridge: SerialBridge) : Interceptor {

    companion object {
        const val POLL_TIMEOUT_HEADER = "X-Poll-Timeout"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Header locale (non spedito all'ESP32/camera, vedi sotto) per far usare alle
        // chiamate di polling in background un timeout piu' corto di quello di default:
        // sono tolleranti a un ciclo perso (il prossimo riparte in pochi secondi), ma se
        // usano il timeout lungo delle azioni utente, un singolo ciclo lento tiene il
        // lock di SerialBridge per tutta la sua durata bloccando qualsiasi azione
        // interattiva (es. cambio impostazioni) dietro di se'.
        val pollTimeoutMs = request.header(POLL_TIMEOUT_HEADER)?.toLongOrNull()

        // Ricostruisce il testo "METODO URL\nHeader: val\n\nbody"
        val sb = StringBuilder()
        sb.append(request.method()).append(' ').append(request.url()).append('\n')
        for (i in 0 until request.headers().size()) {
            val name = request.headers().name(i)
            if (name.equals(POLL_TIMEOUT_HEADER, ignoreCase = true)) continue // solo locale, non va sul filo
            sb.append(name).append(": ").append(request.headers().value(i)).append('\n')
        }
        sb.append('\n')
        request.body()?.let { body ->
            val buffer = okio.Buffer()
            body.writeTo(buffer)
            sb.append(buffer.readUtf8())
        }

        val payload = sb.toString().toByteArray(Charsets.UTF_8)
        val (code, responseBody) = if (pollTimeoutMs != null)
            bridge.sendRequestAndAwait(payload, pollTimeoutMs)
        else
            bridge.sendRequestAndAwait(payload)

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (code > 0) code else 502)
            .message(if (code > 0) "OK" else "Bad Gateway")
            // ResponseBody dai byte grezzi (non da una String intermedia): il body puo'
            // essere binario (es. JPEG di /camera/capture), il MediaType qui non influenza
            // la scelta del converter Retrofit (basata sull'annotation @ResponseFormat).
            .body(ResponseBody.create(null, responseBody))
            .build()
    }
}