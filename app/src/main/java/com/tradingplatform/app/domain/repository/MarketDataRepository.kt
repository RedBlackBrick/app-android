package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.model.SymbolPage
import java.math.BigDecimal

interface MarketDataRepository {
    suspend fun getQuote(symbol: String): Result<Quote>

    /** Convenience overload — full ticker list, no search/pagination (widgets, legacy callers). */
    suspend fun getAvailableSymbols(): Result<List<String>>

    /** Server-side search + offset pagination — backs the watchlist symbol picker. */
    suspend fun getAvailableSymbols(
        search: String?,
        limit: Int = 100,
        offset: Int = 0,
    ): Result<SymbolPage>

    suspend fun getHistory(symbol: String, limit: Int = 30): Result<List<BigDecimal>>
}
