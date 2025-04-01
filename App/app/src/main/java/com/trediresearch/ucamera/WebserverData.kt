package com.trediresearch.ucamera

import com.google.gson.annotations.SerializedName
import java.util.ArrayList

data class  settings(
    @SerializedName("lensposition") var lensposition:Double=0.0,
    @SerializedName("brightness") var brightness:Double=0.0,
    @SerializedName("sharpness") var sharpness:Double=0.0,
    @SerializedName("saturation") var saturation:Double=0.0,
    @SerializedName("exposurevalue") var exposurevalue:Int=0,
    @SerializedName("exposuretime") var exposureTime:Int=0,
    @SerializedName("contrast") var contrast:Double=0.0,
    @SerializedName("gain") var gain:Int=1,

    )


data class response<T>(
    @SerializedName("status") val status:String="",
    @SerializedName("message") val message:ArrayList<String>,
    @SerializedName("data") val data:ArrayList<T>,
)

data class dataset(
    @SerializedName("dataset_id") var dataset_id:Int=0,
    @SerializedName("datasetname") var datasetname:String="",
    @SerializedName("description") var description:String="",
    @SerializedName("interval") var interval:Int=3,



    )

