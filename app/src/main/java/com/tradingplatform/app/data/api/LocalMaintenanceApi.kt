package com.tradingplatform.app.data.api

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * API pour les appels LAN de maintenance vers la Radxa (port 8099).
 * Pas de base URL fixe — @Url dynamique par IP Radxa.
 * Pas d'intercepteurs VPS (pas de CSRF, Auth, ni VPN check — géré au niveau Repository).
 *
 * Le client Retrofit associé doit utiliser "http://localhost/" comme base URL de fallback,
 * car @Url override complètement la base URL à chaque appel.
 */
interface LocalMaintenanceApi {

    @POST
    suspend fun sendCommand(
        @Url url: String,    // "https://{radxa_ip}:8099/command"
        @Body body: RequestBody,   // application/octet-stream (bytes chiffrés libsodium)
    ): Response<ResponseBody>

    @GET
    suspend fun getStatus(
        @Url url: String,    // "https://{radxa_ip}:8099/status"
    ): Response<Map<String, String>>

    @GET
    suspend fun getIdentity(
        @Url url: String,    // "https://{radxa_ip}:8099/identity"
    ): Response<Map<String, String>>
}
