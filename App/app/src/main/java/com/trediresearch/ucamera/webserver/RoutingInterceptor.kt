package com.trediresearch.ucamera.webserver

import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/** Percorso Wi-Fi verso la camera (implementato da [NetworkProbe]). */
interface WifiRoute {
    /** Client diretto se la camera risponde via Wi-Fi, altrimenti null. Bloccante: puo' sondare. */
    fun client(): OkHttpClient?

    /** Una richiesta diretta e' fallita: per un po' si resta sulla radio (vedi [LinkHealth]). */
    fun reportFailure()
}

/**
 * Sceglie il collegamento di OGNI richiesta REST: Wi-Fi se la camera risponde li', radio
 * (bridge seriale) altrimenti. Sta in un punto solo, davanti al bridge, cosi' nessun metodo
 * di [Webserver] deve sapere da dove passa (pattern Strategy sul trasporto).
 *
 * Via Wi-Fi le richieste non aspettano il lock di [SerialBridge], che serve una richiesta
 * alla volta: sono piu' veloci e non si accodano dietro al polling.
 *
 * Se il Wi-Fi cade a meta' strada la richiesta si rimanda via radio solo quando ripeterla
 * e' innocuo (vedi [canResendOnRadio]); altrimenti l'errore risale e vale la verifica che
 * il chiamante gia' fa (p.es. "acquisizione gia' in corso" su un secondo avvio).
 *
 * @param wifi  percorso Wi-Fi, null se non disponibile (nessun Context)
 * @param radio trasporto seriale ([SerialTransportInterceptor]), null senza porta seriale:
 *              in quel caso la richiesta prosegue come normale HTTP sulla rete di default
 * @param onLink notificato con il collegamento usato da ogni richiesta andata a buon fine
 */
class RoutingInterceptor(
    private val wifi: WifiRoute?,
    private val radio: Interceptor?,
    private val onLink: (Link) -> Unit = {},
) : Interceptor {

    companion object {
        private const val TAG = "RoutingInterceptor"

        /** Metodi che si possono ripetere senza effetti diversi (GET legge, PUT riscrive lo stesso valore). */
        private val REPEATABLE_METHODS = setOf("GET", "PUT")

        /**
         * True se una richiesta fallita via Wi-Fi si puo' rimandare via radio:
         *  - la connessione non si e' nemmeno aperta: la camera non l'ha ricevuta;
         *  - oppure il metodo e' ripetibile (GET, PUT). Un PUT exec di AF-S ripetuto rifa'
         *    solo la messa a fuoco.
         * I POST (avvio acquisizione, scatto) potrebbero essere gia' stati eseguiti: non si
         * ripetono alla cieca.
         */
        fun canResendOnRadio(method: String, error: IOException): Boolean =
            neverReachedCamera(error) || method.uppercase() in REPEATABLE_METHODS

        /** Errori che OkHttp solleva prima di aver spedito la richiesta. */
        fun neverReachedCamera(error: IOException): Boolean =
            error is ConnectException || error is NoRouteToHostException || error is UnknownHostException

        /** Toglie gli header che servono solo all'app (non vanno sul filo, vedi SerialTransportInterceptor). */
        fun withoutLocalHeaders(request: Request): Request =
            request.newBuilder().removeHeader(SerialTransportInterceptor.POLL_TIMEOUT_HEADER).build()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val direct = wifi?.client()
        if (direct != null) {
            try {
                val response = direct.newCall(withoutLocalHeaders(request)).execute()
                onLink(Link.WIFI)
                return response
            } catch (e: IOException) {
                wifi?.reportFailure()
                val resend = radio != null && canResendOnRadio(request.method(), e)
                Log.w(TAG, "${request.method()} ${request.url().encodedPath()} fallita via Wi-Fi " +
                        "(${e.javaClass.simpleName}): " + if (resend) "rimando via radio" else "nessun rinvio")
                if (!resend) throw e
            }
        }

        if (radio == null) {
            // Senza porta seriale resta solo l'HTTP diretto sulla rete di default.
            val response = chain.proceed(withoutLocalHeaders(request))
            onLink(Link.WIFI)
            return response
        }
        val response = radio.intercept(chain)
        onLink(Link.RADIO)
        return response
    }
}
