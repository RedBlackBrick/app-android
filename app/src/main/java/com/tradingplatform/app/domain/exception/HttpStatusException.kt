package com.tradingplatform.app.domain.exception

/**
 * Erreur HTTP non-2xx renvoyée par un endpoint VPS, portée jusqu'au Worker appelant pour
 * décider s'il doit retenter (backoff WorkManager) ou abandonner définitivement.
 *
 * [isRetryable] : 5xx (erreur serveur transitoire), 429 (rate limit) et 408 (timeout serveur)
 * méritent un `Result.retry()`. Les autres 4xx (400 payload invalide, 401/403 auth, 404) sont
 * définitifs — retenter avec le même payload échouerait de la même façon en boucle.
 */
class HttpStatusException(
    val code: Int,
    val endpoint: String,
    message: String = "HTTP $code on $endpoint",
) : Exception(message) {
    val isRetryable: Boolean
        get() = code >= 500 || code == 429 || code == 408
}
