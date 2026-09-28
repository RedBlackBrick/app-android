package com.tradingplatform.app.data.api

import com.tradingplatform.app.data.model.MarketDataResponseDto
import com.tradingplatform.app.data.model.QuoteDto
import com.tradingplatform.app.data.model.SymbolListResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface MarketDataApi {
    @GET("v1/market-data/quote/{symbol}")
    suspend fun getQuote(@Path("symbol") symbol: String): Response<QuoteDto>

    /**
     * Server-side search + offset pagination — backend `list_symbols`
     * (trading-platform2 `app/market_data/router.py:232-260`), `SymbolListResponse`
     * (`schemas.py:393-400`). `limit` is bounded [1, 500] server-side (default 100).
     */
    @GET("v1/market-data/symbols")
    suspend fun getSymbols(
        @Query("search") search: String? = null,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): Response<SymbolListResponseDto>

    /**
     * OHLCV range for sparklines — backend `get_history` (`get_range`, ascending —
     * oldest first), `router.py:195-229`. `start`/`end` are required ISO-8601
     * instants server-side. Deliberately NOT `GET /v1/market-data/` (`get_latest`,
     * DESC-ordered): the latter has no `/{symbol}/history` path segment and would
     * require reversing the response; `start`/`end` here just needs a lookback
     * window wide enough to cover `limit` daily bars.
     */
    @GET("v1/market-data/{symbol}/history")
    suspend fun getHistory(
        @Path("symbol") symbol: String,
        @Query("start") start: String,
        @Query("end") end: String,
        @Query("timeframe") timeframe: String = "1d",
        @Query("limit") limit: Int = 1000,
    ): Response<MarketDataResponseDto>
}
