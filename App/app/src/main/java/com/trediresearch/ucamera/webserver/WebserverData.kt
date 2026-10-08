package com.trediresearch.ucamera.webserver

import com.google.gson.TypeAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
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
    // Modalita' di fuoco corrente: "manual" | "afs" (one-shot) | "afc" (continuo).
    // Il plugin la tiene nei settings per renderla leggibile da GET /camera/settings, ma si
    // SCRIVE solo via PUT /camera/exec {"cmd":"focus_mode"} (vedi Webserver.setFocusMode()).
    // Attenzione: set_settings() lato plugin accetta qualunque chiave gia' presente nel suo
    // dizionario, e noi facciamo PUT dell'intero oggetto a ogni tap sui +/-. Il valore qui
    // dentro va quindi SEMPRE quello riletto dal server: se restasse il default locale, il
    // primo tap sulla luminosita' butterebbe la camera fuori da afc rimettendola in manuale.
    @SerializedName("afmode") var afmode:String=FOCUS_MANUAL,
    // Esposizione automatica. Finche' e' true l'AEC/AGC di libcamera riscrive
    // ExposureTime e AnalogueGain a ogni frame, quindi "Tempo di esposizione" e "ISO"
    // non hanno alcun effetto - era esattamente il motivo per cui il cambio ISO non si
    // vedeva. Con AE attivo conta 'exposurevalue' (compensazione EV); con AE spento
    // contano tempo e guadagno. Vedi Window.updateControlsEnabled().
    @SerializedName("aeenable") var aeenable:Boolean=true,
    // Parametri della RICERCA del fuoco: contano solo quando e' la camera a cercarlo
    // (afs/afc), in manuale libcamera li ignora. Come afmode si scrivono via
    // PUT /camera/exec {"cmd":"focus_mode"}, cosi' restano modificabili anche in
    // acquisizione, quando set_settings e' bloccato.
    //
    // Sono NULLABILI di proposito, ed e' la parte importante: Gson omette i campi null,
    // quindi su un device con il plugin vecchio - che non conosce queste chiavi - non
    // vengono mai spediti. Con un default non-null finirebbero nel PUT dell'intero
    // oggetto che facciamo a ogni tap sui +/-, e il server risponderebbe "Unsupported
    // changes to afrange property" a ogni singolo tocco. Null significa quindi "questo
    // device non li espone": l'interfaccia nasconde la riga, vedi
    // Window.updateControlsEnabled().
    @SerializedName("afrange") var afrange:String?=null,
    @SerializedName("afspeed") var afspeed:String?=null,
    // Finestra di messa a fuoco come frazioni del fotogramma [x, y, w, h] in 0..1.
    // Lista VUOTA = fotogramma intero (e' un valore legittimo, non "assente"): solo
    // null significa che il device non supporta il parametro.
    @SerializedName("afwindow") var afwindow:List<Double>?=null,

    )

const val FOCUS_MANUAL = "manual"
const val FOCUS_SINGLE = "afs"
const val FOCUS_CONTINUOUS = "afc"

// Ordine di ciclatura del selettore di fuoco in UI (btn_autofocus).
val FOCUS_MODES = listOf(FOCUS_MANUAL, FOCUS_SINGLE, FOCUS_CONTINUOUS)

// Notazione fotografica standard: dice che si sta guardando lo STATO corrente, non
// l'azione che il tap provochera'. "--" quando le impostazioni non sono ancora arrivate.
fun focusModeLabel(mode: String): String = when (mode) {
    FOCUS_SINGLE -> "AF-S"
    FOCUS_CONTINUOUS -> "AF-C"
    else -> "MF"
}

// Nome esteso, per il messaggio di conferma dopo il cambio.
fun focusModeDescription(mode: String): String = when (mode) {
    FOCUS_SINGLE -> "Autofocus singolo"
    FOCUS_CONTINUOUS -> "Autofocus continuo"
    else -> "Fuoco manuale"
}

// --- Parametri della ricerca del fuoco (solo in afs/afc) --------------------------
// I valori sono quelli ammessi dal plugin: qualunque altra stringa viene RIFIUTATA dal
// server con un errore, non corretta in silenzio. Tenere questi elenchi allineati a
// _AF_RANGES / _AF_SPEEDS in arducam.py.

const val AF_RANGE_NORMAL = "normal"
const val AF_RANGE_MACRO = "macro"
const val AF_RANGE_FULL = "full"

// Estensione della corsa esplorata. E' la leva piu' efficace sull'autofocus continuo:
// restringerla riduce il tempo di aggancio e rende impossibili gli errori verso
// l'estremo opposto. "full" e' la piu' lenta, non la migliore.
val AF_RANGES = listOf(AF_RANGE_NORMAL, AF_RANGE_MACRO, AF_RANGE_FULL)

const val AF_SPEED_NORMAL = "normal"
const val AF_SPEED_FAST = "fast"

val AF_SPEEDS = listOf(AF_SPEED_NORMAL, AF_SPEED_FAST)

// Riquadro centrale del 40% del fotogramma. E' il rimedio al caso classico: a
// fotogramma pieno uno sfondo a forte contrasto puo' "vincere" sul soggetto e la
// camera mette a fuoco dietro di esso.
val AF_WINDOW_CENTER = listOf(0.3, 0.3, 0.4, 0.4)

// Etichette per pulsanti da 56dp: dicono lo STATO corrente, non l'azione del tap.
fun afRangeLabel(range: String?): String = when (range) {
    AF_RANGE_MACRO -> "MACRO"
    AF_RANGE_FULL -> "FULL"
    AF_RANGE_NORMAL -> "NORM"
    else -> "--"
}

fun afSpeedLabel(speed: String?): String = when (speed) {
    AF_SPEED_FAST -> "RAPIDA"
    AF_SPEED_NORMAL -> "STD"
    else -> "--"
}

// Lista vuota = fotogramma intero; null = parametro non supportato dal device.
fun afWindowLabel(window: List<Double>?): String = when {
    window == null -> "--"
    window.isEmpty() -> "PIENO"
    else -> "CENTRO"
}

// Nomi estesi per il toast di conferma: su un pulsante cosi' piccolo la sigla da sola
// non basta a capire cosa si e' appena attivato (stesso ragionamento di
// focusModeDescription).
fun afRangeDescription(range: String?): String = when (range) {
    AF_RANGE_MACRO -> "Ricerca ravvicinata (macro)"
    AF_RANGE_FULL -> "Ricerca su tutta la corsa: piu' lenta"
    AF_RANGE_NORMAL -> "Ricerca a distanze ordinarie"
    else -> "Estensione della ricerca sconosciuta"
}

fun afSpeedDescription(speed: String?): String = when (speed) {
    AF_SPEED_FAST -> "Aggancio rapido"
    AF_SPEED_NORMAL -> "Aggancio standard"
    else -> "Velocita' di aggancio sconosciuta"
}

fun afWindowDescription(window: List<Double>?): String = when {
    window == null -> "Finestra di fuoco sconosciuta"
    window.isEmpty() -> "Fuoco su tutto il fotogramma"
    else -> "Fuoco sul riquadro centrale"
}


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

// Il server serializza altitude come TUPLA Python -> array JSON [valore, "BSL"] (vedi
// common_interfaces/_devices/location_system.py), non come oggetto: Gson su un data class
// fallirebbe con "Expected BEGIN_OBJECT but was BEGIN_ARRAY". Questo adapter accetta
// entrambe le forme (e null), cosi' funziona sia con la forma nativa sia con un'eventuale
// rotta custom che restituisca un oggetto. Vedi anche il gestore Socket.IO location_status
// in Window, che legge gia' correttamente la forma ad array.
class AltitudeInfoAdapter : TypeAdapter<AltitudeInfo?>() {

    override fun write(out: JsonWriter, value: AltitudeInfo?) {
        if (value == null) { out.nullValue(); return }
        out.beginArray()
        if (value.value == null) out.nullValue() else out.value(value.value)
        out.value(value.ref)
        out.endArray()
    }

    override fun read(reader: JsonReader): AltitudeInfo? {
        when (reader.peek()) {
            JsonToken.NULL -> { reader.nextNull(); return null }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                val v = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else reader.nextDouble()
                val r = if (reader.hasNext()) {
                    if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else reader.nextString()
                } else null
                while (reader.hasNext()) reader.skipValue()
                reader.endArray()
                return AltitudeInfo(v, r)
            }
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                var v: Double? = null
                var r: String? = null
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "value" -> v = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else reader.nextDouble()
                        "ref" -> r = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else reader.nextString()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                return AltitudeInfo(v, r)
            }
            else -> { reader.skipValue(); return null }
        }
    }
}

data class LocationInfo(
    @SerializedName("altitude") val altitude: AltitudeInfo? = null,
)

// PUT /camera/exec - comando generico esposto dal server: il webserver legge data["cmd"]
// E data.get("params", {}), quindi senza il campo params non e' possibile invocare
// focus_mode. params viene omesso da Gson quando e' null, cosi' le chiamate che non lo
// usano restano identiche a prima sul filo.
// I valori sono Any e non String perche' 'window' e' una lista di frazioni
// ([x, y, w, h]), non una stringa: Gson la serializza come array JSON, che e' quello
// che il plugin si aspetta. Gli altri parametri restano stringhe.
data class ExecCommandRequest(
    @SerializedName("cmd") val cmd: String,
    @SerializedName("params") val params: Map<String, Any>? = null
)

