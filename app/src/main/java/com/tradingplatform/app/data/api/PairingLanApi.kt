package com.tradingplatform.app.data.api

import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * API pour les appels LAN vers la Radxa (port 8099).
 * Pas de base URL fixe — URL complète passée en paramètre @Url (dynamique par IP Radxa).
 * Pas d'intercepteurs VPS (pas de CSRF, pas d'Auth, pas de VPN check — géré au niveau Repository).
 *
 * Le client Retrofit associé doit utiliser "http://localhost/" comme base URL de fallback,
 * car @Url override complètement la base URL à chaque appel.
 */
interface PairingLanApi {

    @POST
    suspend fun sendPin(
        @Url url: String,    // "http://{radxa_ip}:8099/pin"
        @Body body: RequestBody,   // application/octet-stream (bytes chiffrés libsodium)
    ): Response<Unit>

    @GET
    suspend fun getStatus(
        @Url url: String,    // "http://{radxa_ip}:8099/status"
    ): Response<Map<String, String>>
}
