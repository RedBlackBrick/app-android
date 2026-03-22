package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.Quote

interface MarketDataRepository {
    suspend fun getQuote(symbol: String): Result<Quote>
    suspend fun getAvailableSymbols(): Result<List<String>>
}
