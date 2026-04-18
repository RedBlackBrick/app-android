package com.tradingplatform.app.data.websocket

import kotlin.math.pow

/**
 * Calcul du backoff exponentiel pour la reconnexion WebSocket (Privé + Public).
 *
 * Formule : `min(initialMs * multiplier^attempts, maxMs)` — extraite en pure fonction
 * pour être testable sans dépendances OkHttp / Dispatchers / CoroutineScope.
 *
 * Valeurs par défaut alignées sur la stratégie définie dans
 * [com.tradingplatform.app.data.websocket.PrivateWsClient] :
 *  - 1ère tentative : 5s
 *  - 2ème : 10s
 *  - 3ème : 20s
 *  - ... jusqu'au plafond 300s (5 min)
 *
 * [attempts] est le nombre de tentatives consécutives **avant** celle qui va être
 * planifiée (0 pour la première reconnexion).
 */
object WsBackoff {
    const val INITIAL_MS = 5_000L
    const val MAX_MS = 300_000L
    const val MULTIPLIER = 2.0

    fun computeDelayMs(
        attempts: Int,
        initialMs: Long = INITIAL_MS,
        maxMs: Long = MAX_MS,
        multiplier: Double = MULTIPLIER,
    ): Long {
        require(attempts >= 0) { "attempts must be >= 0, got $attempts" }
        val raw = (initialMs * multiplier.pow(attempts)).toLong()
        return minOf(raw, maxMs)
    }
}
