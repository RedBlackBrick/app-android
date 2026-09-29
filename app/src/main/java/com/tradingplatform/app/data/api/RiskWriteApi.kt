package com.tradingplatform.app.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Écriture de risque : activation du kill switch d'UN portefeuille. Interface DÉDIÉE construite sur
 * le Retrofit `@Named("write")` (`retryOnConnectionFailure(false)`, jamais rejouée), fournie par
 * `di/InboxRiskModule.kt`.
 *
 * **Activation seule.** La levée (`DELETE` sur la même URL) n'est volontairement PAS exposée : elle
 * se fait sur le web. Le 200 renvoie l'état posé (scope, active, reason, activated_by,
 * activated_at), ignoré ici (`Response<Unit>`) : l'appelant relit `RiskRepository.getRiskStatus`.
 */
interface RiskWriteApi {
    @POST("v1/risk/portfolios/{portfolio_id}/kill-switch")
    suspend fun activatePortfolioKillSwitch(
        @Path("portfolio_id") portfolioId: String,
        @Body body: PortfolioKillSwitchActivateRequestDto,
    ): Response<Unit>
}
