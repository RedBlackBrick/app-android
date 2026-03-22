package com.tradingplatform.app.data.api

import com.tradingplatform.app.data.model.QuoteDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

interface MarketDataApi {
    @GET("v1/market-data/quote/{symbol}")
    suspend fun getQuote(@Path("symbol") symbol: String): Response<QuoteDto>

    @GET("v1/market-data/symbols")
    suspend fun getSymbols(): Response<List<String>>
}
