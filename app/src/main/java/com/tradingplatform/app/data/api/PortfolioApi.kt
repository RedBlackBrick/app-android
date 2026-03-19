package com.tradingplatform.app.data.api

import com.tradingplatform.app.data.model.PerformanceResponseDto
import com.tradingplatform.app.data.model.PortfolioDetailDto
import com.tradingplatform.app.data.model.PositionListResponseDto
import com.tradingplatform.app.data.model.TransactionListResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface PortfolioApi {
    @GET("v1/portfolios/{portfolio_id}/positions")
    suspend fun getPositions(
        @Path("portfolio_id") portfolioId: String,
        @Query("status") status: String = "open",
    ): Response<PositionListResponseDto>

    @GET("v1/portfolios/{portfolio_id}/performance")
    suspend fun getPerformance(
        @Path("portfolio_id") portfolioId: String,
    ): Response<PerformanceResponseDto>

    @GET("v1/portfolios/{portfolio_id}")
    suspend fun getPortfolioDetail(
        @Path("portfolio_id") portfolioId: String,
    ): Response<PortfolioDetailDto>

    @GET("v1/portfolios/{portfolio_id}/transactions")
    suspend fun getTransactions(
        @Path("portfolio_id") portfolioId: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("symbol") symbol: String? = null,
    ): Response<TransactionListResponseDto>
}
