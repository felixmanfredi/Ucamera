package com.trediresearch.ucamera

import android.util.Log
import io.socket.client.IO
import io.socket.emitter.Emitter
import io.socket.client.Socket
import io.socket.engineio.client.Transport
import io.socket.engineio.client.transports.WebSocket
import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException





class SocketIOConnection()  {
    lateinit var socket: Socket
     var baseurl=""
    fun init(url:String="") {
        baseurl=url
        try {

            var options=IO.Options()
            options.transports=arrayOf(WebSocket.NAME)
            options.path="/socket"
            socket=IO.socket(url,options)
            socket.on(Socket.EVENT_CONNECT,Emitter.Listener {

            })






        } catch (e: URISyntaxException) {
            socket.disconnect()
            Log.e(javaClass.name, e.message.orEmpty())
            Thread.sleep(2000)
            init();
        }
    }
}