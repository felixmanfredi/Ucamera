package com.trediresearch.ucamera.webserver

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
enum class ConverterFormat {
    XML,
    JSON
}

interface WebserverApi {
    @Headers("Content-Type: application/json")
    @GET("version")
    @RequestFormat(ConverterFormat.JSON)
    @ResponseFormat("application/json")
    fun getVersion():Call<response<version>>

    @Headers("Content-Type: application/json")
    @PUT("camera/settings")
    @RequestFormat(ConverterFormat.JSON)
    @ResponseFormat("application/json")
    fun setSettings(@Body body:settings):Call<response<Nothing>>

    @GET("camera/settings")
    @RequestFormat(ConverterFormat.JSON)
    @ResponseFormat("application/json")
    fun getSettings():Call<response<settings>>


    @POST("camera/capture")
    @RequestFormat(ConverterFormat.JSON)
    @ResponseFormat("image/jpeg")
    fun capture(@Body body: CaptureRequest): Call<ResponseBody>


    // X-Poll-Timeout: usata solo dal polling periodico (Window.statusPollRunnable) -
    // timeout piu' corto del default (vedi SerialTransportInterceptor.POLL_TIMEOUT_HEADER)
    // cosi' un ciclo lento/perso non tiene il lock di SerialBridge bloccando azioni
    // interattive dell'utente (es. cambio impostazioni) per 20s dietro di se'.
    @Headers("X-Poll-Timeout: 6000")
    @GET("datasets/")
    @RequestFormat(ConverterFormat.JSON)
    @ResponseFormat("application/json")
    fun getDatasets():Call<response<List<DatasetInfo>>>

    // Rotta custom (non presente nel firmware/webserver vendor originale) aggiunta
    // sul device per esporre l'ultimo fix GPS/altimetro via REST - vedi LocationInfo.
    @Headers("X-Poll-Timeout: 6000")
    @GET("location_system/status")
    @RequestFormat(ConverterFormat.JSON)
    @ResponseFormat("application/json")
    fun getLocationStatus():Call<response<LocationInfo>>

    @POST("datasets/start/")
    @RequestFormat(ConverterFormat.JSON)
    @ResponseFormat("application/json")
    fun startDataset(@Body body:dataset):Call<response<dataset>>


    @Headers("Content-Type: application/json")
    @PUT("datasets/stop")
    @RequestFormat(ConverterFormat.JSON)
    @ResponseFormat("application/json")
    fun stopDataset():Call<response<Nothing>>

    @POST("datasets/start_video/")
    @RequestFormat(ConverterFormat.JSON)
    @ResponseFormat("application/json")
    fun startVideo(@Body body:dataset):Call<response<dataset>>

    @Headers("Content-Type: application/json")
    @PUT("camera/exec")
    @RequestFormat(ConverterFormat.JSON)
    @ResponseFormat("application/json")
    fun execCameraCommand(@Body body: ExecCommandRequest): Call<response<Nothing>>
}



@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequestFormat(val value: ConverterFormat =ConverterFormat.JSON)

annotation class ResponseFormat(val value: String)
