package com.trediresearch.ucamera.webserver

/**
 * Collegamento usato da una richiesta verso la camera: Wi-Fi (IP diretto) o radio Skydroid
 * (bridge seriale dell'ESP32). Mostrato all'utente come badge, vedi Window.showLink().
 */
enum class Link { WIFI, RADIO }

/**
 * Stato di salute del Wi-Fi con isteresi, separato da [NetworkProbe] perche' e' pura logica
 * (testabile senza Android).
 *
 * - Wi-Fi buono: l'esito vale [upTtlMs], poi si ricontrolla.
 * - Wi-Fi fallito (sonda o richiesta vera): si resta sulla radio per [downBackoffMs] prima
 *   di riprovare. Senza questa attesa una rete che va e viene farebbe rimbalzare ogni
 *   comando fra i due collegamenti, pagando ogni volta il tentativo Wi-Fi fallito.
 */
class LinkHealth(
    private val upTtlMs: Long = 10_000L,
    private val downBackoffMs: Long = 30_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var checkedAt: Long? = null
    private var up = false

    /** True se l'ultimo esito e' scaduto (o non c'e') e va rifatta la sonda. */
    @Synchronized
    fun needsCheck(): Boolean {
        val at = checkedAt ?: return true
        val validity = if (up) upTtlMs else downBackoffMs
        return clock() - at >= validity
    }

    /** Ultimo esito noto: true = la camera risponde via Wi-Fi. */
    @Synchronized
    fun isUp(): Boolean = up

    /** Registra l'esito di una sonda o di una richiesta vera. */
    @Synchronized
    fun record(ok: Boolean) {
        up = ok
        checkedAt = clock()
    }

    /** Rete cambiata: si dimentica tutto e la prossima richiesta rifa' la sonda. */
    @Synchronized
    fun reset() {
        checkedAt = null
        up = false
    }
}
