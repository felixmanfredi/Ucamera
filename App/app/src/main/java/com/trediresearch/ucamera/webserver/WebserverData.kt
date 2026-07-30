package com.trediresearch.ucamera.webserver

import com.google.gson.annotations.SerializedName
import java.util.ArrayList

data class  version(
    @SerializedName("version") var version: String=""
    
)

data class  settings(
    @SerializedName("lensposition") var lensposition:Double=0.0,
    @SerializedName("brightness") var brightness:Double=0.0,
    @SerializedName("sharpness") var sharpness:Double=0.0,
    @SerializedName("saturation") var saturation:Double=0.0,
    @SerializedName("exposurevalue") var exposurevalue:Int=0,
    @SerializedName("exposuretime") var exposureTime:Int=0,
    @SerializedName("contrast") var contrast:Double=0.0,
    @SerializedName("gain") var gain:Double=1.0,

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
    @SerializedName("acquisition_device") var acquisition_device: String="camera",
    @SerializedName("interval") var interval: Double?=5.0,



    )

data class CaptureRequest(
    @SerializedName("flash") val flash: Boolean = false)

// Sottoinsieme minimo di GET /datasets/ - serve a Webserver.getAcquisitionStatus()/
// isAcquisitionRunning() per sapere se un'acquisizione e' in corso (anche se avviata
// da un'altra sessione) e mostrare "Dataset X Foto Y" (Gson ignora gli altri campi
// della risposta, non serve mappare l'intero oggetto dataset).
data class DatasetInfo(
    @SerializedName("dataset_id") val dataset_id: Int = 0,
    @SerializedName("completed") val completed: Boolean = true,
    @SerializedName("items") val items: Int = 0,
)

// Risultato di Webserver.getAcquisitionStatus(), usato per il polling periodico dello
// stato (sostituisce il device_status via Socket.IO, che richiede un vero percorso IP
// e non funziona sul solo bridge seriale/radio Skydroid).
data class AcquisitionStatus(
    val running: Boolean,
    val datasetId: Int,
    val items: Int,
)

// GET /location_system/status - rotta custom (non vendor) aggiunta a
// communication/webserver.py sul device per esporre l'ultimo fix GPS/altimetro via
// REST, dato che il location_status via Socket.IO non e' raggiungibile sul solo
// bridge seriale/radio Skydroid. Mappiamo solo altitude (usata per il depth display),
// Gson ignora latitude/longitude/timestamp che il server include comunque.
data class AltitudeInfo(
    @SerializedName("value") val value: Double? = null,
    @SerializedName("ref") val ref: String? = null,
)

data class LocationInfo(
    @SerializedName("altitude") val altitude: AltitudeInfo? = null,
)

// PUT /camera/exec - comando generico gia' esposto dal server vendor (arriva a
// CameraInterface.exec(command, **params)); "autofocus" e' gestito da noi in
// arducam.py, vedi Webserver.triggerAutofocus().
data class ExecCommandRequest(
    @SerializedName("cmd") val cmd: String
)

