package com.trediresearch.ucamera.webserver

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody

class SerialTransportInterceptor(private val bridge: SerialBridge) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Ricostruisce il testo "METODO URL\nHeader: val\n\nbody"
        val sb = StringBuilder()
        sb.append(request.method()).append(' ').append(request.url()).append('\n')
        for (i in 0 until request.headers().size()) {
            sb.append(request.headers().name(i)).append(": ").append(request.headers().value(i)).append('\n')
        }
        sb.append('\n')
        request.body()?.let { body ->
            val buffer = okio.Buffer()
            body.writeTo(buffer)
            sb.append(buffer.readUtf8())
        }

        val payload = sb.toString().toByteArray(Charsets.UTF_8)
        val (code, responseBody) = bridge.sendRequestAndAwait(payload)

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (code > 0) code else 502)
            .message(if (code > 0) "OK" else "Bad Gateway")
            .body(  ResponseBody.create(MediaType.parse("application/json"), responseBody))
            .build()
    }
}