package com.trediresearch.ucamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.lang.reflect.Type
import java.net.InetAddress


class Webserver {

    lateinit var retrofit: Retrofit
    lateinit var apiservice: WebserverApi

    fun init(url:String):Boolean {

        try {

            retrofit = Retrofit.Builder()
                .baseUrl(url)
                //.addConverterFactory(GsonConverterFactory.create())
                .addConverterFactory(MultipleConverterFactory())
                .build()

            apiservice = retrofit.create(WebserverApi::class.java)

            return true
        }catch (e:Exception){
            Log.e("Ucamera",e.message.toString())
        }
        return false
    }




    fun setSettings(settings: settings): Boolean {
       try{

            var resp = apiservice.setSettings(settings).execute()

            var sessionResponse = resp.body()

            if (sessionResponse != null) {
                if (sessionResponse.status == "success") {
                    return true
                }
            }
        }catch (e:Exception){

        }

        return false
    }

    fun getVersion():version{
        try{
            var resp = apiservice.getVersion().execute()

            var sessionResponse = resp.body()

            if (sessionResponse != null) {
                if (sessionResponse.status == "success") {
                    return sessionResponse.data[0]
                }
            }
        }
        catch (e:java.net.ConnectException){
            throw  java.net.ConnectException()
        }
        catch (e:Exception){
            throw  java.net.ConnectException()
            Log.e("UCamera",e.message.toString())
        }

        return version()
    }

    fun getSettings(): settings {
        try{
            var resp = apiservice.getSettings().execute()

            var sessionResponse = resp.body()

            if (sessionResponse != null) {
                if (sessionResponse.status == "success") {
                    return sessionResponse.data[0]
                }
            }
        }
        catch (e:java.net.ConnectException){
            throw  java.net.ConnectException()
        }
        catch (e:Exception){
            Log.e("UCamera",e.message.toString())
            throw  java.net.ConnectException()
        }

        return settings()

    }

    fun capture():Bitmap?{
        try {
            var resp = apiservice.capture().execute()
            var sessionResponse = resp.body()?.byteStream()
            if (sessionResponse != null) {
                val image: Bitmap = BitmapFactory.decodeStream(sessionResponse);
                return image
            }
        }catch (e:Exception){

        }
        return null
    }


    fun startDataset(dataset: dataset): Int {
        try {

            var resp = apiservice.startDataset(dataset).execute()

            var sessionResponse = resp.body()

            if (sessionResponse != null) {
                if (sessionResponse.status == "success") {
                    return sessionResponse.data[0].dataset_id
                }else{
                    for(m in sessionResponse.message) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(App.activity, m, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }catch (e:Exception){

        }

        return -1
    }

    fun stopDataset():Boolean {

       try {
            var resp = apiservice.stopDataset().execute()

            var sessionResponse = resp.body()

            if (sessionResponse != null) {
                if (sessionResponse.status == "success") {
                    return true
                }
            }
       }catch (e:Exception){

       }


        return false
    }

    fun startVideo(dataset: dataset): Int{
        try {

            var resp = apiservice.startVideo(dataset).execute()

            var sessionResponse = resp.body()

            if (sessionResponse != null) {
                if (sessionResponse.status == "success") {
                    return sessionResponse.data[0].dataset_id
                }else{
                    for(m in sessionResponse.message) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(App.activity, m, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }catch (e:Exception){

        }

        return -1
    }

}

class MultipleConverterFactory : Converter.Factory() {

    private val jsonFactory= GsonConverterFactory.create()
    private val textFactory= ScalarsConverterFactory.create()


    override fun requestBodyConverter(type: Type, parameterAnnotations: Array<Annotation?>, methodAnnotations: Array<Annotation?>, retrofit: Retrofit): Converter<*, RequestBody>? {
        methodAnnotations.forEach { annotation ->
            if (annotation is RequestFormat) {
                return when (annotation.value) {
                    ConverterFormat.JSON -> jsonFactory.requestBodyConverter(type, parameterAnnotations, methodAnnotations, retrofit)

                    else ->  textFactory.requestBodyConverter(type, parameterAnnotations, methodAnnotations, retrofit)
                }
            }
        }
        return null
    }



    @RequiresApi(Build.VERSION_CODES.P)
    override fun responseBodyConverter(type: Type, annotations: Array<Annotation?>, retrofit: Retrofit): Converter<ResponseBody?, *>? {
        annotations.forEach { annotation ->
            if (annotation is ResponseFormat) {
                return when (annotation.value) {
                    "application/json" -> jsonFactory.responseBodyConverter(type, annotations, retrofit)
                    "image/jpeg"-> textFactory.responseBodyConverter(type, annotations, retrofit)
                    else -> jsonFactory.responseBodyConverter(type, annotations, retrofit)
                }
            }

        }
        return null
    }
}