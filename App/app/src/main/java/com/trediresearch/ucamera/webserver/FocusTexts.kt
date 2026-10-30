package com.trediresearch.ucamera.webserver

import java.util.Locale

// Testi della scheda Fuoco. Funzioni pure, separate da Window per poterle provare con i
// test JVM: decidono COSA dire all'utente, Window decide solo dove scriverlo.

/** Nome breve del modo, sotto la sigla del pulsante (MF / AF-S / AF-C). */
fun focusModeName(mode: String): String = when (mode) {
    FOCUS_SINGLE -> "Singolo"
    FOCUS_CONTINUOUS -> "Continuo"
    else -> "Manuale"
}

/** Cosa fa la lente nel modo in uso: la riga "In uso" della scheda. */
fun focusModeBehaviour(mode: String): String = when (mode) {
    FOCUS_SINGLE -> "lente ferma dove l'AF l'ha messa"
    FOCUS_CONTINUOUS -> "la camera rimette a fuoco da sola"
    else -> "la lente resta dove la metti"
}

/** Parametri della ricerca in forma compatta, es. "MACRO · STD · 40%". */
fun afSearchSummary(s: settings): String =
    "${afRangeLabel(s.afrange)} · ${afSpeedLabel(s.afspeed)} · ${afWindowLabel(s.afwindow)}"

enum class OutcomeKind { OK, ERROR, INFO }

/** Esito dell'ultimo cambio di modo, scritto nella scheda (oltre al toast). */
data class FocusOutcome(val kind: OutcomeKind, val text: String)

private fun lensText(lens: Double): String = String.format(Locale.getDefault(), "%.2f", lens)

/**
 * Esito di un cambio di modo, ricavato dalle impostazioni RILETTE dal server ([after]),
 * non da quelle chieste: se la camera ha fatto altro, il testo dice il vero.
 * [after] null = rilettura persa (sotto radio): si omette la posizione della lente.
 * Ritorna null quando non c'e' nulla da aggiungere al modo evidenziato (AF-C riuscito).
 */
fun focusModeOutcome(requested: String, ok: Boolean, after: settings?, serverMessage: String?): FocusOutcome? {
    val lens = after?.let { " · lente " + lensText(it.lensposition) } ?: ""
    return when {
        ok && requested == FOCUS_SINGLE -> FocusOutcome(OutcomeKind.OK, "✔ AF-S riuscito$lens")
        ok && requested == FOCUS_MANUAL -> FocusOutcome(OutcomeKind.INFO,
            "Manuale" + (after?.let { " · lente bloccata a " + lensText(it.lensposition) } ?: ""))
        ok -> null
        requested == FOCUS_SINGLE -> FocusOutcome(OutcomeKind.ERROR,
            "✖ AF-S non riuscito" + (after?.let { " · rimasto in ${focusModeName(it.afmode)}$lens" } ?: ""))
        else -> FocusOutcome(OutcomeKind.ERROR,
            "✖ " + (serverMessage ?: "Cambio a ${focusModeName(requested)} non riuscito"))
    }
}
