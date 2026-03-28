package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.Quote
import java.math.BigDecimal

interface MarketDataRepository {
    suspend fun getQuote(symbol: String): Result<Quote>
    suspend fun getAvailableSymbols(): Result<List<String>>
    suspend fun getHistory(symbol: String, limit: Int = 30): Result<List<BigDecimal>>
}
