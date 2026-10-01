package com.trediresearch.ucamera.webserver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.gson.GsonBuilder
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

private const val TAG = "UCamera"

// Esito di una PUT /camera/settings o di un PUT /camera/exec. Il server risponde SEMPRE
// 200/"success" anche quando rifiuta delle chiavi: l'unico canale d'errore e' l'array
// "message" (es. "Unsupported changes to <key> property", "Cannot Set Settings during
// recording!"), che prima veniva scartato lasciando all'utente solo un toast generico.
data class CommandResult(
    val ok: Boolean,
    val messages: List<String> = emptyList(),
) {
    val message: String? get() = messages.firstOrNull()
}

// Perche' uno scatto di prova non ha prodotto un'immagine. Serve a distinguere in campo
// casi che prima collassavano tutti in "Bitmap? == null" e nello stesso toast.
enum class CaptureFailure {
    NONE,
    SERVER_ERROR,     // envelope JSON di errore (es. acquisizione in corso), con messaggio
    BRIDGE_ERROR,     // 502 sintetico di SerialTransportInterceptor: nessuna risposta utile
    EMPTY_BODY,       // 2xx ma corpo vuoto/troncato: tipico dell'heap esaurito sull'ESP32
    CORRUPT_IMAGE,    // byte ricevuti ma non decodificabili
    TRANSPORT_ERROR,  // timeout del bridge / IO
}

data class CaptureResult(
    val bitmap: Bitmap? = null,
    val httpCode: Int = -1,
    val bytesReceived: Int = 0,
    val failure: CaptureFailure = CaptureFailure.NONE,
    val serverMessage: String? = null,
) {
    // Messaggio da mostrare all'utente: quello del server quando c'e', altrimenti uno
    // specifico per il tipo di fallimento (non il generico "Errore durante lo scatto").
    fun userMessage(): String = serverMessage ?: when (failure) {
        CaptureFailure.SERVER_ERROR -> "La camera ha rifiutato lo scatto"
        CaptureFailure.BRIDGE_ERROR -> "Nessuna risposta dalla camera (bridge)"
        CaptureFailure.EMPTY_BODY -> "Immagine non ricevuta: risposta vuota o troncata"
        CaptureFailure.CORRUPT_IMAGE -> "Immagine ricevuta ma non leggibile"
        CaptureFailure.TRANSPORT_ERROR -> "Comunicazione con la camera non riuscita"
        CaptureFailure.NONE -> "Errore durante lo scatto di prova"
    }
}

class Webserver {

    companion object {
        // Sentinel per startDataset()/startVideo(): il server ha rifiutato l'avvio
        // perche' un'acquisizione e' gia' aperta (non e' un errore, e' un
        // disallineamento di stato tra app e server - vedi Window.startAcquisition()).
        const val ALREADY_RUNNING = -2

        // Dopo questi errori consecutivi su GET /location_system/status il polling della
        // profondita' si disattiva da solo: quella rotta NON e' esposta dal server (i dati
        // di posizione viaggiano su Socket.IO/TCP, che richiedono un percorso IP vero), e
        // continuare a interrogarla brucia uno slot del bridge seriale ogni 5 secondi per
        // un 404. Se invece sul device la rotta custom c'e' davvero, il polling prosegue.
        private const val DEPTH_FAILURES_BEFORE_DISABLE = 3
    }

    lateinit var retrofit: Retrofit
    lateinit var apiservice: WebserverApi

    private var bridge: SerialBridge? = null
    private var httpClient: OkHttpClient? = null

    // Percorso IP diretto, usato SOLO dallo scatto di prova (vedi NetworkProbe).
    private var probe: NetworkProbe? = null

    private var depthFailures = 0

    @Volatile
    var depthPollingEnabled = true
        private set

    fun init(url: String, serialPort: SerialPortConnection? = null, context: Context? = null): Boolean {

        val clientBuilder = OkHttpClient.Builder()
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .connectTimeout(2, TimeUnit.SECONDS)

        // REST-over-serial: when a SerialPortConnection is supplied, every Retrofit call is
        // tunneled over it instead of real HTTP (`url` is still needed as Retrofit's base URL).
        if (serialPort != null) {
            val b = SerialBridge(App.activity, serialPort)
            b.connect()
            bridge = b
            clientBuilder.addInterceptor(SerialTransportInterceptor(b))
        }

        val client = clientBuilder.build()
        httpClient = client
        try {

            retrofit = Retrofit.Builder()
                .baseUrl(url)
                .addConverterFactory(MultipleConverterFactory())
                .client(client)
                .build()

            apiservice = retrofit.create(WebserverApi::class.java)

            // La sonda IP esiste solo se abbiamo un Context: senza, resta tutto sul
            // percorso seriale. Nota che App.CurrentApp qui non e' utilizzabile - la
            // classe App non e' dichiarata nel manifest, quindi onCreate() non gira mai.
            if (context != null) {
                probe = NetworkProbe(context, url).also { it.start() }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, e.message.toString())
        }
        return false
    }

    // Rilascia tutto quello che init() ha acquisito. Va chiamata sul Webserver PRECEDENTE
    // prima di crearne uno nuovo (Window.updateConnection() ne costruisce uno a ogni
    // riconnessione): senza, ogni SerialBridge resta registrato come delegate sulla porta
    // seriale e dopo N riconnessioni ogni risposta viene parsata N volte, completando
    // altrettanti future orfani.
    fun shutdown() {
        try {
            probe?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "shutdown/probe: " + e.message)
        }
        probe = null
        try {
            bridge?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "shutdown/bridge: " + e.message)
        }
        bridge = null
        try {
            httpClient?.dispatcher()?.executorService()?.shutdown()
            httpClient?.connectionPool()?.evictAll()
        } catch (e: Exception) {
            Log.e(TAG, "shutdown/http: " + e.message)
        }
        httpClient = null
    }

    fun setSettings(settings: settings): CommandResult {
        try {
            val resp = apiservice.setSettings(settings).execute()
            val sessionResponse = resp.body()
            val messages = sessionResponse.messagesOrEmpty()

            if (sessionResponse != null && sessionResponse.status == "success") {
                // Il server risponde "success" anche quando scarta delle chiavi: se c'e' un
                // messaggio va comunque mostrato, altrimenti un parametro rifiutato resta
                // invisibile e l'utente vede il valore cambiare solo a schermo.
                return CommandResult(true, messages)
            }
            return CommandResult(false, messages)
        } catch (e: Exception) {
            Log.e(TAG, "setSettings: " + e.message.toString())
            // La risposta puo' essersi persa sul bridge seriale/radio anche se il set
            // e' riuscito lato server (stesso problema visto per start/stop dataset,
            // vedi verifiche con isAcquisitionRunning()): rilegge le impostazioni reali
            // invece di assumere un errore che magari non c'e' stato.
            try {
                if (getSettings().matches(settings)) return CommandResult(true)
            } catch (e2: Exception) {
                Log.e(TAG, "setSettings verify: " + e2.message.toString())
            }
        }

        return CommandResult(false)
    }

    fun getVersion(): version {
        try {
            val resp = apiservice.getVersion().execute()
            val sessionResponse = resp.body()

            if (sessionResponse != null && sessionResponse.status == "success") {
                sessionResponse.dataOrEmpty().getOrNull(0)?.let { return it }
            }
            // Risposta arrivata ma inutilizzabile (es. 308 non seguito, envelope di errore):
            // e' comunque un segnale di "non raggiungibile" per chi ci chiama.
            throw ConnectException("Risposta /version non valida (HTTP " + resp.code() + ")")
        } catch (e: ConnectException) {
            throw e
        } catch (e: Exception) {
            // Preserva la causa: distinguere "timeout del bridge" da "host irraggiungibile"
            // serve a capire cosa sta fallendo, e il messaggio vuoto di prima lo impediva.
            throw ConnectException(e.javaClass.simpleName + ": " + e.message)
        }
    }

    fun getSettings(): settings {
        try {
            val resp = apiservice.getSettings().execute()
            val sessionResponse = resp.body()

            if (sessionResponse != null && sessionResponse.status == "success") {
                sessionResponse.dataOrEmpty().getOrNull(0)?.let { return it }
            }
            throw ConnectException("Risposta /camera/settings non valida (HTTP " + resp.code() + ")")
        } catch (e: ConnectException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getSettings: " + e.message.toString())
            throw ConnectException(e.javaClass.simpleName + ": " + e.message)
        }
    }

    // Lo stesso endpoint puo' restituire un JPEG, un envelope JSON di errore (il server
    // risponde cosi' anche su /camera/capture, es. "Cannot capture test image during
    // acquisition!"), un 502 sintetico del bridge, oppure un corpo vuoto/troncato quando
    // l'ESP32 non riesce ad allocare l'intera risposta. Classificarli serve a sapere in
    // campo quale dei problemi si sta guardando, invece di avere un unico toast generico.
    // Senza Wi-Fi lo scatto viene comunque tentato sulla seriale: l'IP e' una
    // scorciatoia opportunistica, non un prerequisito.
    //  1. se la sonda dice che l'IP risponde -> tentativo diretto;
    //  2. se quel tentativo fallisce per motivi di trasporto -> fallback seriale,
    //     invalidando la cache della sonda;
    //  3. se la camera ha RIFIUTATO lo scatto (envelope JSON, es. acquisizione in
    //     corso) non si ritenta: rifiuterebbe allo stesso modo anche via seriale,
    //     sprecando fino a 20s di timeout del bridge;
    //  4. se la sonda dice che l'IP non c'e' -> direttamente seriale, senza perdere
    //     tempo in un tentativo gia' destinato a fallire.
    fun capture(): CaptureResult {
        val direct = try {
            probe?.reachableApi()
        } catch (e: Exception) {
            Log.e(TAG, "capture/probe: " + e.message); null
        }

        if (direct != null) {
            val result = captureVia(direct, "IP")
            if (result.bitmap != null || result.failure == CaptureFailure.SERVER_ERROR) {
                return result
            }
            Log.w(TAG, "capture: percorso IP fallito (${result.failure}), passo alla seriale")
            probe?.invalidate()
        }

        val first = captureVia(apiservice, "seriale")
        if (first.bitmap != null || first.failure == CaptureFailure.SERVER_ERROR) return first

        // Un solo ritentativo, e solo sui fallimenti di TRASPORTO (timeout, corpo vuoto o
        // troncato): sono gli esiti che dipendono da come e' andato quel giro - heap
        // dell'ESP32 al limite, richiesta corrotta da una collisione con il flusso video -
        // e che hanno quindi una probabilita' reale di andare diversamente.
        // Se invece la camera ha RIFIUTATO lo scatto (envelope JSON, es. acquisizione in
        // corso) non si ritenta: rifiuterebbe allo stesso modo, sprecando fino a 20s.
        Log.w(TAG, "capture: primo tentativo seriale fallito (${first.failure}), ritento una volta")
        return captureVia(apiservice, "seriale/retry")
    }

    private fun captureVia(service: WebserverApi, transport: String): CaptureResult {
        try {
            val resp = service.capture(CaptureRequest()).execute()
            val code = resp.code()

            val bytes = (resp.body() ?: resp.errorBody())?.bytes() ?: ByteArray(0)

            if (bytes.isEmpty()) {
                Log.e(TAG, "capture[$transport]: corpo vuoto (HTTP $code)")
                return CaptureResult(
                    httpCode = code,
                    failure = if (code == 502) CaptureFailure.BRIDGE_ERROR else CaptureFailure.EMPTY_BODY,
                )
            }

            // Envelope JSON invece dell'immagine: contiene il motivo vero del rifiuto.
            if (bytes[0] == '{'.code.toByte() || bytes[0] == '['.code.toByte()) {
                val msg = parseServerMessage(bytes)
                Log.e(TAG, "capture[$transport]: risposta JSON invece di immagine (HTTP $code): $msg")
                return CaptureResult(
                    httpCode = code,
                    bytesReceived = bytes.size,
                    failure = CaptureFailure.SERVER_ERROR,
                    serverMessage = msg,
                )
            }

            val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (image == null) {
                // Byte ricevuti ma non decodificabili: tipicamente un JPEG troncato, cioe'
                // la firma del limite di dimensione sul percorso seriale.
                Log.e(TAG, "capture[$transport]: ${bytes.size} byte non decodificabili (HTTP $code)")
                return CaptureResult(
                    httpCode = code,
                    bytesReceived = bytes.size,
                    failure = CaptureFailure.CORRUPT_IMAGE,
                )
            }

            Log.i(TAG, "capture[$transport]: ok, ${bytes.size} byte, ${image.width}x${image.height}")
            return CaptureResult(bitmap = image, httpCode = code, bytesReceived = bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "capture[$transport]: " + e.message.toString())
            return CaptureResult(failure = CaptureFailure.TRANSPORT_ERROR)
        }
    }

    private fun parseServerMessage(bytes: ByteArray): String? {
        return try {
            val env = GSON.fromJson(String(bytes, Charsets.UTF_8), ErrorEnvelope::class.java)
            env?.message?.filterNotNull()?.firstOrNull()
        } catch (e: Exception) {
            null
        }
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
                val datasets = sessionResponse.dataOrEmpty().getOrNull(0) ?: return null
                val latest = datasets.maxByOrNull { it.dataset_id } ?: return AcquisitionStatus(false, 0, 0)
                return AcquisitionStatus(!latest.completed, latest.dataset_id, latest.items)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAcquisitionStatus (polling): " + e.message.toString())
        }
        return null
    }

    fun isAcquisitionRunning(): Boolean = getAcquisitionStatus()?.running ?: false

    // Profondita' in metri (sotto il livello del mare, "BSL") dall'ultimo fix
    // altimetro - null se non c'e' ancora un fix o se il riferimento e' ASL (sopra
    // il livello del mare, non rilevante per il depth display).
    fun getDepthMeters(): Double? {
        if (!depthPollingEnabled) return null
        try {
            val resp = apiservice.getLocationStatus().execute()
            val sessionResponse = resp.body()
            if (sessionResponse != null && sessionResponse.status == "success") {
                depthFailures = 0
                val altitude = sessionResponse.dataOrEmpty().getOrNull(0)?.altitude ?: return null
                if (altitude.ref == "BSL" && altitude.value != null) return altitude.value
                return null
            }
            noteDepthFailure("HTTP " + resp.code())
        } catch (e: Exception) {
            noteDepthFailure(e.message ?: e.javaClass.simpleName)
        }
        return null
    }

    private fun noteDepthFailure(reason: String) {
        depthFailures++
        if (depthFailures >= DEPTH_FAILURES_BEFORE_DISABLE && depthPollingEnabled) {
            depthPollingEnabled = false
            Log.w(
                TAG, "getDepthMeters: disattivato dopo $depthFailures errori consecutivi " +
                        "($reason). La rotta /location_system/status non e' esposta dal server: " +
                        "la profondita' arriva solo via Socket.IO, che richiede un percorso IP."
            )
        }
    }

    // Messaggi dell'ultimo start*/stop* fallito, da mostrare all'utente al posto del
    // testo generico (prima venivano toastati da qui, cioe' da un thread di rete).
    @Volatile
    var lastServerMessages: List<String> = emptyList()
        private set

    fun startDataset(dataset: dataset): Int {
        try {

            val resp = apiservice.startDataset(dataset).execute()
            val sessionResponse = resp.body()

            if (sessionResponse != null) {
                if (sessionResponse.status == "success") {
                    sessionResponse.dataOrEmpty().getOrNull(0)?.let { return it.dataset_id }
                } else {
                    lastServerMessages = sessionResponse.messagesOrEmpty()
                    for (m in lastServerMessages) {
                        Log.e(TAG, "startDataset: server: $m")
                        if (m.contains("already running", ignoreCase = true)) return ALREADY_RUNNING
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startDataset: " + e.message.toString())
            // La risposta puo' essersi persa sul bridge seriale/radio anche se lo
            // start e' riuscito lato server: verifica lo stato reale invece di
            // assumere un errore.
            if (isAcquisitionRunning()) return ALREADY_RUNNING
        }

        return -1
    }

    fun stopDataset(): Boolean {

        try {
            val resp = apiservice.stopDataset().execute()
            val sessionResponse = resp.body()

            if (sessionResponse != null && sessionResponse.status == "success") {
                return true
            }
            lastServerMessages = sessionResponse.messagesOrEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "stopDataset: " + e.message.toString())
        }

        // La risposta puo' essersi persa sul bridge seriale/radio anche se lo stop
        // e' riuscito lato server (stesso problema visto per startDataset): verifica
        // lo stato reale invece di assumere un errore che magari non c'e' stato.
        return !isAcquisitionRunning()
    }

    // PUT /camera/exec {"cmd":"focus_mode","params":{"mode":...}} - unico comando di fuoco
    // esposto dal plugin: "manual" (lente pilotata da lensposition), "afs" (one-shot, rimette
    // a fuoco a ogni invocazione), "afc" (continuo). Passa da exec e non dai settings proprio
    // perche' set_settings e' bloccato durante la registrazione, mentre il fuoco deve poter
    // essere cambiato anche in missione.
    fun setFocusMode(mode: String): CommandResult {
        try {
            val resp = apiservice.execCameraCommand(
                ExecCommandRequest("focus_mode", mapOf("mode" to mode))
            ).execute()
            val sessionResponse = resp.body()
            if (sessionResponse != null && sessionResponse.status == "success") {
                return CommandResult(true, sessionResponse.messagesOrEmpty())
            }
            // Device non ancora aggiornato: il plugin vecchio conosce solo "autofocus" e
            // risponde errore a focus_mode. Il one-shot resta ottenibile, gli altri modi no.
            if (mode == FOCUS_SINGLE) {
                Log.w(TAG, "setFocusMode: focus_mode rifiutato, ritento con il vecchio 'autofocus'")
                if (triggerLegacyAutofocus()) return CommandResult(true)
            }
            return CommandResult(false, sessionResponse.messagesOrEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "setFocusMode: " + e.message.toString())
        }
        return CommandResult(false)
    }

    private fun triggerLegacyAutofocus(): Boolean {
        return try {
            val resp = apiservice.execCameraCommand(ExecCommandRequest("autofocus")).execute()
            resp.body()?.status == "success"
        } catch (e: Exception) {
            Log.e(TAG, "triggerLegacyAutofocus: " + e.message.toString())
            false
        }
    }

    fun startVideo(dataset: dataset): Int {
        try {

            val resp = apiservice.startVideo(dataset).execute()
            val sessionResponse = resp.body()

            if (sessionResponse != null) {
                if (sessionResponse.status == "success") {
                    sessionResponse.dataOrEmpty().getOrNull(0)?.let { return it.dataset_id }
                } else {
                    lastServerMessages = sessionResponse.messagesOrEmpty()
                    for (m in lastServerMessages) {
                        Log.e(TAG, "startVideo: server: $m")
                        if (m.contains("already running", ignoreCase = true)) return ALREADY_RUNNING
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startVideo: " + e.message.toString())
            if (isAcquisitionRunning()) return ALREADY_RUNNING
        }

        return -1
    }
}

// Envelope minimo usato per leggere il motivo di un rifiuto quando /camera/capture
// restituisce JSON al posto dell'immagine.
private data class ErrorEnvelope(
    val status: String? = null,
    val message: List<String?>? = null,
)

// Gson non garantisce che i campi non-null di Kotlin siano valorizzati: il server omette
// "data" quando non c'e' payload (ResponseFactory lo aggiunge solo se non vuoto), quindi
// quei campi possono arrivare null a dispetto del tipo dichiarato. Si leggono solo da qui.
@Suppress("SENSELESS_COMPARISON")
fun response<*>?.messagesOrEmpty(): List<String> {
    if (this == null) return emptyList()
    val m: ArrayList<String>? = this.message
    return m?.filterNotNull() ?: emptyList()
}

@Suppress("SENSELESS_COMPARISON")
fun <T> response<T>?.dataOrEmpty(): List<T> {
    if (this == null) return emptyList()
    val d: ArrayList<T>? = this.data
    return d ?: emptyList()
}

// Confronto tollerante usato dalla riverifica di setSettings(): i valori passano per JSON e
// per eventuali clamp lato camera, e il passo 0.1 della luminosita' accumula errore binario
// (0.1+0.1+0.1 = 0.30000000000000004). Un == su Double non combacia piu' dopo pochi tap,
// rendendo morto il fallback. afmode e' escluso: non si scrive via settings.
fun settings.matches(other: settings): Boolean {
    fun eq(a: Double, b: Double) = kotlin.math.abs(a - b) < 1e-6
    return eq(lensposition, other.lensposition) &&
            eq(brightness, other.brightness) &&
            eq(sharpness, other.sharpness) &&
            eq(saturation, other.saturation) &&
            eq(contrast, other.contrast) &&
            eq(gain, other.gain) &&
            exposurevalue == other.exposurevalue &&
            exposureTime == other.exposureTime &&
            aeenable == other.aeenable
}

internal val GSON = GsonBuilder()
    // altitude arriva come array [valore, "BSL"] (tupla Python), non come oggetto.
    .registerTypeAdapter(AltitudeInfo::class.java, AltitudeInfoAdapter())
    .create()

class MultipleConverterFactory : Converter.Factory() {

    private val jsonFactory = GsonConverterFactory.create(GSON)
    private val textFactory = ScalarsConverterFactory.create()


    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<Annotation?>,
        methodAnnotations: Array<Annotation?>,
        retrofit: Retrofit
    ): Converter<*, RequestBody>? {
        methodAnnotations.forEach { annotation ->
            if (annotation is RequestFormat) {
                return when (annotation.value) {
                    ConverterFormat.JSON -> jsonFactory.requestBodyConverter(type, parameterAnnotations, methodAnnotations, retrofit)

                    else -> textFactory.requestBodyConverter(type, parameterAnnotations, methodAnnotations, retrofit)
                }
            }
        }
        return null
    }


    override fun responseBodyConverter(type: Type, annotations: Array<Annotation?>, retrofit: Retrofit): Converter<ResponseBody?, *>? {
        annotations.forEach { annotation ->
            if (annotation is ResponseFormat) {
                return when (annotation.value) {
                    "application/json" -> jsonFactory.responseBodyConverter(type, annotations, retrofit)
                    "image/jpeg" -> textFactory.responseBodyConverter(type, annotations, retrofit)
                    else -> jsonFactory.responseBodyConverter(type, annotations, retrofit)
                }
            }

        }
        return null
    }
}
