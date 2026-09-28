package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.MarketDataApi
import com.tradingplatform.app.data.local.db.dao.QuoteDao
import com.tradingplatform.app.data.model.toDomain
import com.tradingplatform.app.data.model.toEntity
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.model.SymbolPage
import com.tradingplatform.app.domain.repository.MarketDataRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarketDataRepositoryImpl @Inject constructor(
    private val marketDataApi: MarketDataApi,
    private val quoteDao: QuoteDao,
) : MarketDataRepository {

    // TTL quotes : 10 min — pour cohérence offline dans QuoteWidget (CLAUDE.md §2)
    private val QUOTE_TTL_MS = 10 * 60 * 1000L

    /**
     * Déduplication des requêtes quote en vol (P5 fix).
     *
     * Si plusieurs sources (Dashboard, PositionDetail, Widget) demandent le même symbole
     * simultanément, une seule requête réseau est effectuée — les autres attendent le résultat.
     *
     * Pattern : CompletableDeferred plutôt que CoroutineScope.async pour éviter que la
     * cancellation d'un appelant n'annule le Deferred pour tous les autres.
     * Le Deferred est retiré de la map dès qu'il est complété (pas de données stales).
     */
    private val inFlightQuotes = ConcurrentHashMap<String, CompletableDeferred<Result<Quote>>>()

    override suspend fun getQuote(symbol: String): Result<Quote> {
        val upperSymbol = symbol.uppercase()

        // Fast path : une requête identique est déjà en vol — réutiliser son résultat
        val existing = inFlightQuotes[upperSymbol]
        if (existing != null) {
            return existing.await()
        }

        // Créer un nouveau Deferred. putIfAbsent retourne null si c'est nous le premier,
        // ou le Deferred existant si un autre thread a gagné la course.
        val deferred = CompletableDeferred<Result<Quote>>()
        val winner = inFlightQuotes.putIfAbsent(upperSymbol, deferred)
        if (winner != null) {
            // Un autre thread a inséré entre notre check et notre put — attendre le sien
            return winner.await()
        }

        // Nous sommes le premier demandeur — exécuter la requête réelle.
        // supervisorScope isole la cancellation : si l'appelant est annulé, le Deferred
        // est quand même complété pour les autres.
        val result = supervisorScope {
            runCatching {
                val response = marketDataApi.getQuote(upperSymbol)
                if (!response.isSuccessful) {
                    error("Get quote failed: HTTP ${response.code()}")
                }
                val quote = response.body()?.toDomain() ?: error("Empty quote response")

                // Persiste dans Room pour le QuoteWidget (offline-first) — transaction atomique
                val now = System.currentTimeMillis()
                quoteDao.upsertAndPurge(
                    quote.toEntity(syncedAt = now),
                    cutoffMillis = now - QUOTE_TTL_MS,
                )

                quote
            }
        }

        // Compléter le Deferred et le retirer immédiatement pour permettre un nouveau fetch
        deferred.complete(result)
        inFlightQuotes.remove(upperSymbol)

        return result
    }

    override suspend fun getAvailableSymbols(): Result<List<String>> =
        getAvailableSymbols(search = null, limit = DEFAULT_SYMBOLS_PAGE_SIZE, offset = 0)
            .map { page -> page.items.map { it.ticker } }

    override suspend fun getAvailableSymbols(
        search: String?,
        limit: Int,
        offset: Int,
    ): Result<SymbolPage> = runCatching {
        val response = marketDataApi.getSymbols(
            search = search?.trim()?.ifBlank { null },
            limit = limit,
            offset = offset,
        )
        if (!response.isSuccessful) {
            error("Get symbols failed: HTTP ${response.code()}")
        }
        val body = response.body() ?: error("Empty symbols response")
        SymbolPage(
            items = body.symbols.filter { it.isActive }.map { it.toDomain() },
            hasMore = body.hasMore,
            nextOffset = body.offset + body.symbols.size,
        )
    }

    override suspend fun getHistory(symbol: String, limit: Int): Result<List<BigDecimal>> = runCatching {
        val upperSymbol = symbol.uppercase()
        val end = Instant.now()
        val start = end.minus(HISTORY_LOOKBACK_DAYS, ChronoUnit.DAYS)
        val response = marketDataApi.getHistory(
            symbol = upperSymbol,
            start = DateTimeFormatter.ISO_INSTANT.format(start),
            end = DateTimeFormatter.ISO_INSTANT.format(end),
            timeframe = "1d",
            limit = limit,
        )
        if (!response.isSuccessful) {
            error("Get history failed: HTTP ${response.code()}")
        }
        val body = response.body() ?: error("Empty history response")
        // GET /{symbol}/history (get_range) returns ascending (oldest first) already —
        // unlike GET /v1/market-data/ (get_latest), which is DESC and would need
        // asReversed(). No reversal is applied here.
        body.data.map { it.close }
    }

    private companion object {
        const val DEFAULT_SYMBOLS_PAGE_SIZE = 100
        const val HISTORY_LOOKBACK_DAYS = 45L
    }
}
