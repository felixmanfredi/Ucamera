package com.trediresearch.ucamera

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
    @PUT("camera/settings")
    @RequestFormat(ConverterFormat.JSON)
    @ResponseFormat("application/json")
    fun setSettings(@Body body:settings):Call<response<Nothing>>

    @GET("camera/settings")
    @RequestFormat(ConverterFormat.JSON)
    @ResponseFormat("application/json")
    fun getSettings():Call<response<settings>>


    @GET("camera/capture")
    @ResponseFormat("image/jpeg")
    fun capture():Call<ResponseBody>


    @POST("datasets/start/")
    @RequestFormat(ConverterFormat.JSON)
    @ResponseFormat("application/json")
    fun startDataset(@Body body:dataset):Call<response<dataset>>


    @Headers("Content-Type: application/json")
    @PUT("datasets/stop")
    @RequestFormat(ConverterFormat.JSON)
    @ResponseFormat("application/json")
    fun stopDataset():Call<response<Nothing>>

    @POST("datasets/start/")
    @RequestFormat(ConverterFormat.JSON)
    @ResponseFormat("application/json")
    fun startVideo(@Body body:dataset):Call<response<dataset>>
}



@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequestFormat(val value: ConverterFormat =ConverterFormat.JSON)

annotation class ResponseFormat(val value: String)
