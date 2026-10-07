package com.tradingplatform.app.data.api

import com.tradingplatform.app.data.model.VigieDto
import retrofit2.Response
import retrofit2.http.GET

/**
 * Vigie « Refuge » (Retrofit normal, lecture seule, tout utilisateur authentifié) — un seul appel
 * porte tout : score, familles, indicateurs, régime, refuge et courbe 90 jours. L'historique
 * (`/v1/vigie/history`) n'est pas consommé par le mobile.
 */
interface VigieApi {
    @GET("v1/vigie")
    suspend fun getVigie(): Response<VigieDto>
}
