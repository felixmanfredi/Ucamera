package com.trediresearch.ucamera.webserver

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Decide se la camera e' raggiungibile via IP (Wi-Fi) e, in caso affermativo, fornisce
 * un client REST che ci parla direttamente invece di passare dal bridge seriale.
 *
 * Serve a un solo caso d'uso: lo scatto di prova. E' la richiesta con la risposta piu'
 * grande (un JPEG da decine di KB) e quella che sul percorso seriale soffre di piu' -
 * l'ESP32 deve tenerne due copie in heap senza PSRAM, e il frame seriale ha un campo
 * lunghezza a 16 bit. Tutto il resto (impostazioni, polling, stream FPV) resta sulla
 * seriale, che e' l'unico percorso disponibile quando si e' solo sotto radio Skydroid.
 *
 * Due dettagli non ovvi:
 *
 * 1. **Binding esplicito alla rete Wi-Fi.** La radio Skydroid non e' una rete IP: se il
 *    telefono ha anche i dati mobili attivi, la rete di default e' quella, e una
 *    richiesta all'IP della camera verrebbe instradata li' e fallirebbe sempre. Il client
 *    viene quindi costruito sulla `Network` del Wi-Fi (socketFactory + dns), invece di
 *    usare `bindProcessToNetwork`, che e' globale di processo e romperebbe il resto.
 *
 * 2. **Sonda funzionale, non ping.** ICMP e' spesso filtrato e per tempi affidabili
 *    servirebbe root. Si usa `GET /version/` con timeout corto, e il risultato vive in
 *    cache per [CACHE_TTL_MS] cosi' non si paga una sonda per ogni scatto.
 */
class NetworkProbe(context: Context, private val baseUrl: String) {

    companion object {
        private const val TAG = "NetworkProbe"

        // Quanto dura l'esito di una sonda prima di rifarla. Abbastanza lungo da non
        // sondare a ogni scatto, abbastanza corto da accorgersi in fretta che il Wi-Fi
        // e' tornato (o sparito) durante una sessione.
        private const val CACHE_TTL_MS = 10_000L

        // La sonda non deve mai far aspettare l'utente: se entro questo tempo l'IP non
        // risponde, si va direttamente sulla seriale.
        private const val PROBE_TIMEOUT_MS = 1_500L

        // Timeout delle richieste vere su IP. Generoso rispetto alla sonda (lo scatto
        // comprende l'acquisizione lato camera) ma molto sotto i 20s del bridge seriale.
        private const val DIRECT_TIMEOUT_MS = 8_000L
    }

    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Volatile
    private var wifiNetwork: Network? = null

    // Retrofit costruito sulla Network corrente. Va ricostruito quando la rete cambia,
    // perche' socketFactory e dns sono legati a quella specifica Network.
    private var boundNetwork: Network? = null
    private var boundApi: WebserverApi? = null

    private var lastProbeMs = 0L
    private var lastProbeOk = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork = network
            invalidate()
            Log.i(TAG, "Rete Wi-Fi disponibile: $network")
        }

        override fun onLost(network: Network) {
            if (wifiNetwork == network) {
                wifiNetwork = null
                invalidate()
                Log.i(TAG, "Rete Wi-Fi persa: $network")
            }
        }
    }

    private var registered = false

    @Synchronized
    fun start() {
        if (registered) return
        val cm = connectivity ?: return
        // Nessun filtro su NET_CAPABILITY_INTERNET: l'access point della camera non da'
        // accesso a internet, e richiederlo escluderebbe proprio la rete che ci serve.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: Exception) {
            // Manca ACCESS_NETWORK_STATE o il sistema rifiuta: si resta sul solo seriale.
            Log.e(TAG, "registerNetworkCallback: " + e.message)
        }
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        try {
            connectivity?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.e(TAG, "unregisterNetworkCallback: " + e.message)
        }
        registered = false
        wifiNetwork = null
        boundNetwork = null
        boundApi = null
    }

    /** Forza una nuova sonda alla prossima richiesta (rete cambiata o tentativo fallito). */
    @Synchronized
    fun invalidate() {
        lastProbeMs = 0L
        lastProbeOk = false
    }

    /**
     * Il client diretto se la camera risponde via IP, altrimenti null. Chiamata
     * bloccante (puo' eseguire la sonda): va usata da un thread di background.
     */
    @Synchronized
    fun reachableApi(): WebserverApi? {
        val api = apiForCurrentNetwork() ?: return null

        val now = System.currentTimeMillis()
        if (now - lastProbeMs < CACHE_TTL_MS) {
            return if (lastProbeOk) api else null
        }

        lastProbeMs = now
        lastProbeOk = probe(api)
        return if (lastProbeOk) api else null
    }

    private fun probe(api: WebserverApi): Boolean {
        return try {
            val resp = api.getVersion().execute()
            val ok = resp.isSuccessful && resp.body()?.status == "success"
            if (!ok) Log.w(TAG, "Sonda IP: risposta inattesa (HTTP ${resp.code()})")
            ok
        } catch (e: Exception) {
            Log.i(TAG, "Sonda IP fallita: " + e.message)
            false
        }
    }

    private fun apiForCurrentNetwork(): WebserverApi? {
        val network = wifiNetwork ?: return null
        val cached = boundApi
        if (cached != null && boundNetwork == network) return cached

        val client = OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // callTimeout e' l'unico che limita davvero la durata totale della chiamata;
            // read/write da soli non bastano, ed e' il motivo per cui i 2s configurati
            // sul client seriale non hanno mai avuto effetto (li' c'e' un interceptor
            // applicativo, che non e' soggetto ai timeout di socket).
            .callTimeout(DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // Entrambi necessari: la socketFactory instrada i socket sulla rete Wi-Fi e
            // il dns risolve su quella stessa rete. Senza, con i dati mobili attivi la
            // richiesta uscirebbe dalla rete sbagliata.
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    network.getAllByName(hostname).toList()
            })
            .build()

        return try {
            val api = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(MultipleConverterFactory())
                .client(client)
                .build()
                .create(WebserverApi::class.java)
            boundNetwork = network
            boundApi = api
            api
        } catch (e: Exception) {
            Log.e(TAG, "Costruzione client diretto fallita: " + e.message)
            null
        }
    }
}
