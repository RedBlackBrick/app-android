package com.tradingplatform.app.data.api

import com.tradingplatform.app.data.model.BatchPnlRequestDto
import com.tradingplatform.app.data.model.BatchPnlResponseDto
import com.tradingplatform.app.data.model.DashboardOverviewDto
import com.tradingplatform.app.data.model.PerformanceResponseDto
import com.tradingplatform.app.data.model.PnlResponseDto
import com.tradingplatform.app.data.model.PortfolioBrokerConnectionDto
import com.tradingplatform.app.data.model.PortfolioDetailDto
import com.tradingplatform.app.data.model.PositionDto
import com.tradingplatform.app.data.model.TransactionListResponseDto
import com.tradingplatform.app.data.model.ValueHistoryResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface PortfolioApi {
    @GET("v1/portfolios/{portfolio_id}/positions")
    suspend fun getPositions(
        @Path("portfolio_id") portfolioId: String,
        @Query("status") status: String = "open",
    ): Response<List<PositionDto>>

    @GET("v1/portfolios/{portfolio_id}/performance")
    suspend fun getPerformance(
        @Path("portfolio_id") portfolioId: String,
    ): Response<PerformanceResponseDto>

    @GET("v1/portfolios/{portfolio_id}")
    suspend fun getPortfolioDetail(
        @Path("portfolio_id") portfolioId: String,
    ): Response<PortfolioDetailDto>

    /**
     * P&L « depuis la création » : `period` ne filtre que les compteurs de trades (montants et
     * pourcentage de vie entière, contrat §8.1). Pour un P&L jour/semaine/mois → [getBatchPnl].
     */
    @GET("v1/portfolios/{portfolio_id}/pnl")
    suspend fun getPnl(
        @Path("portfolio_id") portfolioId: String,
        @Query("period") period: String = "day",
    ): Response<PnlResponseDto>

    @GET("v1/portfolios/{portfolio_id}/transactions")
    suspend fun getTransactions(
        @Path("portfolio_id") portfolioId: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("symbol") symbol: String? = null,
    ): Response<TransactionListResponseDto>

    /**
     * `POST /v1/portfolios/batch/pnl` — P&L par période (day | week | month ; ytd/all → 422),
     * 1..50 ids. Lecture pure (POST à cause du corps) : non destructive et idempotente, elle passe
     * par le client normal (CSRF requis côté backend, injecté par `CsrfInterceptor`).
     */
    @POST("v1/portfolios/batch/pnl")
    suspend fun getBatchPnl(
        @Body body: BatchPnlRequestDto,
    ): Response<BatchPnlResponseDto>

    /** `GET /v1/dashboard/overview` — valeur courante et capital initial de tous mes portefeuilles. */
    @GET("v1/dashboard/overview")
    suspend fun getDashboardOverview(): Response<DashboardOverviewDto>

    /**
     * `GET /v1/portfolios/{id}/value-history` — AUCUNE pagination côté backend : sans `start_date`
     * tout l'historique est renvoyé ; `raw` = un snapshot toutes les 3-5 min. Toujours borner
     * (`start_date` + `granularity` = `daily` | `raw`). [startDate] : `YYYY-MM-DD` (UTC, inclus).
     */
    @GET("v1/portfolios/{portfolio_id}/value-history")
    suspend fun getValueHistory(
        @Path("portfolio_id") portfolioId: String,
        @Query("start_date") startDate: String,
        @Query("granularity") granularity: String,
        @Query("end_date") endDate: String? = null,
    ): Response<ValueHistoryResponseDto>

    /**
     * `GET /v1/portfolios/{id}/broker-connection` — 200 avec corps JSON `null` quand aucune
     * connexion n'est configurée (le body est alors `null`).
     */
    @GET("v1/portfolios/{portfolio_id}/broker-connection")
    suspend fun getBrokerConnection(
        @Path("portfolio_id") portfolioId: String,
    ): Response<PortfolioBrokerConnectionDto?>
}
