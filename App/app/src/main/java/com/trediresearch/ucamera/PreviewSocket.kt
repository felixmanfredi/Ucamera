package com.trediresearch.ucamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class PreviewSocket {

    companion object{
        lateinit var image: Bitmap
        var image_updated = false
    }


    private var serverSocket: ServerSocket? = null
    private val working = AtomicBoolean(true)
    var socket: Socket? = null

    fun init() {
        Thread {
            run()
        }.start()
    }


    fun run() {
        println("${Thread.currentThread()} Runnable Thread Started.")
        //startSocketServerTCP()
    }


    fun startSocketClientTCP(callback: (result: Bitmap?) -> Unit){
        while(true) {
            try {
                socket = Socket()
                socket!!.connect(InetSocketAddress("192.168.0.103", 10001),5000)
                var inputStream = socket!!.getInputStream()
                while (true) {
                    Log.i("UCamera",inputStream.available().toString());
                    if (inputStream.available() > 0) {
                        val i = BitmapFactory.decodeStream(inputStream);
                        if (i != null) {
                            callback(i)
                        }
                    } else {
                        break;
                    }
                }
            }catch (e:Exception){
                socket!!.close()
                Thread.sleep(1000);
            }
        }


    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun startSocketServerTCP(callback: (result: Bitmap?) -> Unit) {
        try {
            serverSocket = ServerSocket(10001)
            serverSocket!!.receiveBufferSize=230454
            //serverSocket!!.soTimeout = 1000;
            if (serverSocket != null) {
                while (true) {
                    socket = serverSocket!!.accept()
                    //socket!!.keepAlive = true
                    //socket!!.tcpNoDelay = false

                    if (socket != null) {
                        while (!socket!!.isClosed) {
                            val dataInputStream = socket!!.getInputStream()
                            var b=ByteArray(dataInputStream.available())
                            var nbyte=dataInputStream.buffered().read(b)


                            if (nbyte >=0) {
                                try {
                                    //val b=dataInputStream.readAllBytes()
                                    val i = BitmapFactory.decodeByteArray(b,0,b.size);

                                    if (i != null) {
                                        callback(i)
                                    }
                                } catch (e: Exception) {
                                    print(e.message)
                                }

                            }else{
                                socket!!.close()
                            }

                        }

                    } else {

                    }
                }

            }
        }

        catch (e: IOException) {
            e.printStackTrace()
            try {
                socket?.close()
            } catch (ex: IOException) {
                ex.printStackTrace()
            }
        }
        catch (e:Exception){
            e.printStackTrace()
        }
    }
}

class TcpClientHandler(private val dataInputStream: InputStream, private val dataOutputStream: DataOutputStream) : Thread() {
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun run() {
        while (true) {
            try {
                if(dataInputStream.available() > 0){
                    val buf = ByteArray(12000)

                    val data=dataInputStream.read(buf,0,12000)
                    try{
                        val i = BitmapFactory.decodeByteArray(buf,0,buf.size);
                        if (i != null) {
                            PreviewSocket.image = i
                            PreviewSocket.image_updated=true
                        }
                    }catch (e:Exception){
                        print(e.message)
                    }

                    //Log.i(TAG, "Received: " + dataInputStream.readUTF())
                    //dataOutputStream.writeUTF("Hello Client")
                    sleep(2000L)
                }
            } catch (e: IOException) {
                e.printStackTrace()
                try {
                    dataInputStream.close()
                    dataOutputStream.close()
                } catch (ex: IOException) {
                    ex.printStackTrace()
                }
            } catch (e: InterruptedException) {
                e.printStackTrace()
                try {
                    dataInputStream.close()
                    dataOutputStream.close()
                } catch (ex: IOException) {
                    ex.printStackTrace()
                }
            }
        }
    }

    companion object {
        private val TAG = TcpClientHandler::class.java.simpleName
    }

}