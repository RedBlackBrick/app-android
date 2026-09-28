package com.tradingplatform.app.data.session

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cache in-memory du JWT access token, partagé entre [AuthInterceptor] et [TokenAuthenticator].
 *
 * Évite un accès disque (EncryptedSharedPreferences AES256-GCM) sur chaque requête HTTP.
 * [AuthInterceptor] lit uniquement [accessToken] — il n'existe AUCUN fallback DataStore :
 * holder vide ⇒ 401 synthétique + logout forcé.
 *
 * ## Qui peuple le cache
 * - `TradingApplication.onCreate` : preload asynchrone depuis EncryptedDataStore au démarrage.
 * - [com.tradingplatform.app.domain.usecase.auth.GetAuthContextUseCase] : si le holder est
 *   encore vide quand il lit le token sur disque (preload pas terminé), il le peuple — la
 *   porte de navigation, qui attend ce use case, implique donc un holder peuplé.
 * - AuthRepositoryImpl (login / 2fa verify) et [TokenAuthenticator] (refresh).
 *
 * ## Invariants
 * - Toute écriture dans EncryptedDataStore(ACCESS_TOKEN) DOIT aussi appeler [setToken].
 * - Toute suppression (logout / clearAll) DOIT aussi appeler [clear].
 * - Le cache est toujours au moins aussi frais que le disque (write holder first, persist second).
 *
 * ## Thread-safety
 * [@Volatile] garantit la visibilité inter-thread (happens-before). Pas de CAS nécessaire :
 * les écritures concurrentes (TokenAuthenticator vs AuthRepositoryImpl) sont sérialisées
 * par le Mutex de TokenAuthenticator ou par le flux séquentiel login/2fa.
 */
@Singleton
class TokenHolder @Inject constructor() {

    @Volatile
    var accessToken: String? = null
        private set

    fun setToken(token: String) {
        accessToken = token
    }

    fun clear() {
        accessToken = null
    }
}
