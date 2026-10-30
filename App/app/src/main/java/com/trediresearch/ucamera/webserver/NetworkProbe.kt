package com.trediresearch.ucamera.webserver

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Percorso Wi-Fi verso la camera: decide se la camera risponde via IP e, in caso
 * affermativo, fornisce un client REST diretto. Lo usa [RoutingInterceptor] per OGNI
 * richiesta (dalla 2.0; prima serviva solo allo scatto di prova): via Wi-Fi le richieste
 * non passano dal bridge seriale, che serve una richiesta alla volta.
 *
 * Tre dettagli non ovvi:
 *
 * 1. **Binding esplicito alla rete Wi-Fi.** La radio Skydroid non e' una rete IP: se il
 *    telefono ha anche i dati mobili attivi, la rete di default e' quella, e una
 *    richiesta all'IP della camera verrebbe instradata li' e fallirebbe sempre. Il client
 *    viene quindi costruito sulla `Network` del Wi-Fi (socketFactory + dns), invece di
 *    usare `bindProcessToNetwork`, che e' globale di processo e romperebbe il resto.
 *
 * 2. **Sonda funzionale, non ping.** ICMP e' spesso filtrato e per tempi affidabili
 *    servirebbe root. Si usa `GET /version/` con timeout corto.
 *
 * 3. **Isteresi** (vedi [LinkHealth]): un Wi-Fi buono si ricontrolla ogni 10 s; dopo un
 *    errore (sonda o richiesta vera) si resta sulla radio per 30 s prima di riprovare.
 */
class NetworkProbe(context: Context, private val baseUrl: String) : WifiRoute {

    companion object {
        private const val TAG = "NetworkProbe"

        // La sonda non deve mai far aspettare l'utente: se entro questo tempo l'IP non
        // risponde, si va direttamente sulla radio.
        private const val PROBE_TIMEOUT_MS = 1_500L

        // Timeout delle richieste vere su IP. Copre un AF-S (tipicamente <2 s) e lo scatto
        // di prova, restando sotto i 20 s del bridge seriale.
        private const val DIRECT_TIMEOUT_MS = 8_000L
    }

    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val health = LinkHealth()

    @Volatile
    private var wifiNetwork: Network? = null

    // Client costruiti sulla Network corrente. Vanno ricostruiti quando la rete cambia,
    // perche' socketFactory e dns sono legati a quella specifica Network.
    private var boundNetwork: Network? = null
    private var boundClient: OkHttpClient? = null
    private var boundProbeClient: OkHttpClient? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork = network
            health.reset()
            Log.i(TAG, "Rete Wi-Fi disponibile: $network")
        }

        override fun onLost(network: Network) {
            if (wifiNetwork == network) {
                wifiNetwork = null
                health.reset()
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
            // Manca ACCESS_NETWORK_STATE o il sistema rifiuta: si resta sulla sola radio.
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
        boundClient = null
        boundProbeClient = null
    }

    override fun reportFailure() {
        health.record(false)
    }

    /**
     * Il client diretto se la camera risponde via Wi-Fi, altrimenti null. Senza rete Wi-Fi
     * risponde subito; altrimenti puo' eseguire la sonda: va chiamata da un thread di
     * background (lo e' sempre: e' dentro una chiamata Retrofit .execute()).
     */
    @Synchronized
    override fun client(): OkHttpClient? {
        val network = wifiNetwork ?: return null
        bindTo(network)
        if (health.needsCheck()) {
            health.record(probe())
        }
        return if (health.isUp()) boundClient else null
    }

    private fun probe(): Boolean {
        val client = boundProbeClient ?: return false
        return try {
            val request = Request.Builder().url(baseUrl.trimEnd('/') + "/version/").build()
            client.newCall(request).execute().use { resp ->
                val ok = resp.isSuccessful
                if (!ok) Log.w(TAG, "Sonda IP: risposta inattesa (HTTP ${resp.code()})")
                ok
            }
        } catch (e: Exception) {
            Log.i(TAG, "Sonda IP fallita: " + e.message)
            false
        }
    }

    private fun bindTo(network: Network) {
        if (boundNetwork == network && boundClient != null) return
        val client = OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // callTimeout e' l'unico che limita davvero la durata totale della chiamata;
            // read/write da soli non bastano.
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
        boundNetwork = network
        boundClient = client
        // Stessa rete e stesso pool, ma la sonda non deve mai durare piu' di PROBE_TIMEOUT_MS.
        boundProbeClient = client.newBuilder()
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}
