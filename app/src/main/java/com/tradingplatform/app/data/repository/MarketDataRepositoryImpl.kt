package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.MarketDataApi
import com.tradingplatform.app.data.local.db.dao.QuoteDao
import com.tradingplatform.app.data.model.toDomain
import com.tradingplatform.app.data.model.toEntity
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.repository.MarketDataRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarketDataRepositoryImpl @Inject constructor(
    private val marketDataApi: MarketDataApi,
    private val quoteDao: QuoteDao,
) : MarketDataRepository {

    // TTL quotes : 10 min — pour cohérence offline dans QuoteWidget (CLAUDE.md §2)
    private val QUOTE_TTL_MS = 10 * 60 * 1000L

    override suspend fun getQuote(symbol: String): Result<Quote> = runCatching {
        val response = marketDataApi.getQuote(symbol)
        if (!response.isSuccessful) {
            error("Get quote failed: HTTP ${response.code()}")
        }
        val quote = response.body()?.toDomain() ?: error("Empty quote response")

        // Persiste dans Room pour le QuoteWidget (offline-first) — purge APRÈS sync réussie
        val now = System.currentTimeMillis()
        quoteDao.upsert(quote.toEntity(syncedAt = now))
        quoteDao.deleteOlderThan(now - QUOTE_TTL_MS)

        quote
    }
}
