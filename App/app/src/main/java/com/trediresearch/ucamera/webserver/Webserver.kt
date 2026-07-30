package com.trediresearch.ucamera.webserver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.trediresearch.ucamera.App
import com.trediresearch.ucamera.video.SerialPortConnection
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.lang.reflect.Type
import java.net.ConnectException
import java.util.concurrent.TimeUnit


class Webserver {

    companion object {
        // Sentinel per startDataset()/startVideo(): il server ha rifiutato l'avvio
        // perche' un'acquisizione e' gia' aperta (non e' un errore, e' un
        // disallineamento di stato tra app e server - vedi Window.startAcquisition()).
        const val ALREADY_RUNNING = -2
    }

    lateinit var retrofit: Retrofit
    lateinit var apiservice: WebserverApi

    fun init(url:String, serialPort: SerialPortConnection? = null):Boolean {

        val clientBuilder = OkHttpClient.Builder()
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .connectTimeout(2, TimeUnit.SECONDS)

        // REST-over-serial: when a SerialPortConnection is supplied, every Retrofit call is
        // tunneled over it instead of real HTTP (`url` is still needed as Retrofit's base URL).
        if (serialPort != null) {
            val bridge = SerialBridge(App.activity, serialPort)
            bridge.connect()
            clientBuilder.addInterceptor(SerialTransportInterceptor(bridge))
        }

        var client = clientBuilder.build()
        try {

            retrofit = Retrofit.Builder()
                .baseUrl(url)
                //.addConverterFactory(GsonConverterFactory.create())
                .addConverterFactory(MultipleConverterFactory())
                .client(client)
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
            Log.e("UCamera","setSettings: "+e.message.toString())
            // La risposta puo' essersi persa sul bridge seriale/radio anche se il set
            // e' riuscito lato server (stesso problema visto per start/stop dataset,
            // vedi verifiche con isAcquisitionRunning()): rilegge le impostazioni reali
            // invece di assumere un errore che magari non c'e' stato.
            try {
                if (getSettings() == settings) return true
            } catch (e2: Exception) {
                Log.e("UCamera","setSettings verify: "+e2.message.toString())
            }
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
        catch (e: ConnectException){
            throw ConnectException()
        }
        catch (e:Exception){
            throw ConnectException()
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
        catch (e: ConnectException){
            throw ConnectException()
        }
        catch (e:Exception){
            Log.e("UCamera",e.message.toString())
            throw ConnectException()
        }

        return settings()

    }

    fun capture():Bitmap?{
        try {
            var resp = apiservice.capture(CaptureRequest()).execute()
            var sessionResponse = resp.body()?.byteStream()
            if (sessionResponse != null) {
                val image: Bitmap = BitmapFactory.decodeStream(sessionResponse);
                return image
            }
        }catch (e:Exception){
            Log.e("UCamera","capture: "+e.message.toString())
        }
        return null
    }


    // Interroga il dataset piu' recente (id piu' alto): se non e' "completed" c'e'
    // un'acquisizione in corso. Usato sia per risincronizzare onAcquisition
    // all'avvio/riconnessione (anche se l'acquisizione e' stata avviata da un'altra
    // sessione) sia dal polling periodico che sostituisce il device_status via
    // Socket.IO (non raggiungibile sul solo bridge seriale/radio Skydroid).
    fun getAcquisitionStatus(): AcquisitionStatus? {
        try {
            val resp = apiservice.getDatasets().execute()
            val sessionResponse = resp.body()
            if (sessionResponse != null && sessionResponse.status == "success") {
                val datasets = sessionResponse.data.getOrNull(0) ?: return null
                val latest = datasets.maxByOrNull { it.dataset_id } ?: return AcquisitionStatus(false, 0, 0)
                return AcquisitionStatus(!latest.completed, latest.dataset_id, latest.items)
            }
        }catch (e:Exception){
            Log.e("UCamera","getAcquisitionStatus (polling): "+e.message.toString())
        }
        return null
    }

    fun isAcquisitionRunning(): Boolean = getAcquisitionStatus()?.running ?: false

    // Profondita' in metri (sotto il livello del mare, "BSL") dall'ultimo fix
    // altimetro - null se non c'e' ancora un fix o se il riferimento e' ASL (sopra
    // il livello del mare, non rilevante per il depth display).
    fun getDepthMeters(): Double? {
        try {
            val resp = apiservice.getLocationStatus().execute()
            val sessionResponse = resp.body()
            if (sessionResponse != null && sessionResponse.status == "success") {
                val altitude = sessionResponse.data.getOrNull(0)?.altitude ?: return null
                if (altitude.ref == "BSL" && altitude.value != null) return altitude.value
            }
        }catch (e:Exception){
            Log.e("UCamera","getDepthMeters (polling): "+e.message.toString())
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
                    var alreadyRunning = false
                    for(m in sessionResponse.message) {
                        if (m.contains("already running", ignoreCase = true)) alreadyRunning = true
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(App.activity, m, Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (alreadyRunning) return ALREADY_RUNNING
                }
            }
        }catch (e:Exception){
            Log.e("UCamera","startDataset: "+e.message.toString())
            // La risposta puo' essersi persa sul bridge seriale/radio anche se lo
            // start e' riuscito lato server: verifica lo stato reale invece di
            // assumere un errore.
            if (isAcquisitionRunning()) return ALREADY_RUNNING
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
           Log.e("UCamera","stopDataset: "+e.message.toString())
       }

        // La risposta puo' essersi persa sul bridge seriale/radio anche se lo stop
        // e' riuscito lato server (stesso problema visto per startDataset): verifica
        // lo stato reale invece di assumere un errore che magari non c'e' stato.
        return !isAcquisitionRunning()
    }

    fun triggerAutofocus(): Boolean {
        try {
            val resp = apiservice.execCameraCommand(ExecCommandRequest("autofocus")).execute()
            val sessionResponse = resp.body()
            if (sessionResponse != null && sessionResponse.status == "success") {
                return true
            }
        }catch (e:Exception){
            Log.e("UCamera","triggerAutofocus: "+e.message.toString())
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
                    var alreadyRunning = false
                    for(m in sessionResponse.message) {
                        if (m.contains("already running", ignoreCase = true)) alreadyRunning = true
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(App.activity, m, Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (alreadyRunning) return ALREADY_RUNNING
                }
            }
        }catch (e:Exception){
            Log.e("UCamera","startVideo: "+e.message.toString())
            if (isAcquisitionRunning()) return ALREADY_RUNNING
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