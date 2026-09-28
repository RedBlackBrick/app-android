package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.MarketDataApi
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.data.local.db.dao.QuoteDao
import com.tradingplatform.app.data.model.toDomain
import com.tradingplatform.app.data.model.toEntity
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.model.SymbolPage
import com.tradingplatform.app.domain.repository.MarketDataRepository
import com.tradingplatform.app.domain.util.runCatchingCancellable
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarketDataRepositoryImpl @Inject constructor(
    private val marketDataApi: MarketDataApi,
    private val quoteDao: QuoteDao,
    private val applicationScope: CoroutineScope,
) : MarketDataRepository {

    /**
     * Déduplication des requêtes quote en vol (P5 fix, revu PR 3.3 — audit #10 / B-misc-1).
     *
     * Si plusieurs sources (Dashboard, PositionDetail, Widget) demandent le même symbole
     * simultanément, une seule requête réseau est effectuée — les autres attendent le résultat.
     *
     * Pattern : le fetch réel tourne dans [applicationScope] (détaché de tout appelant),
     * via [CoroutineScope.async]. La cancellation d'un appelant n'annule que son propre
     * `await()` — jamais le Deferred partagé, donc jamais les autres appelants en attente.
     * `invokeOnCompletion` retire l'entrée de la map dès que le Deferred se termine, pour
     * qu'un appel ultérieur déclenche un nouveau fetch.
     */
    private val inFlightQuotes = ConcurrentHashMap<String, Deferred<Result<Quote>>>()

    override suspend fun getQuote(symbol: String): Result<Quote> {
        val upperSymbol = symbol.uppercase()

        // Fast path : une requête identique est déjà en vol — réutiliser son résultat.
        // Si l'appelant est annulé pendant cet await(), seul lui est affecté — le Deferred
        // partagé (applicationScope) continue pour les autres appelants.
        inFlightQuotes[upperSymbol]?.let { return it.await() }

        // LAZY : on ne démarre le fetch que si on gagne effectivement la course putIfAbsent —
        // sinon on jetterait un Deferred déjà en train d'exécuter un appel réseau dupliqué.
        val deferred = applicationScope.async(start = CoroutineStart.LAZY) {
            runCatchingCancellable {
                val response = marketDataApi.getQuote(upperSymbol)
                if (!response.isSuccessful) {
                    error("Get quote failed: HTTP ${response.code()}")
                }
                val quote = response.body()?.toDomain() ?: error("Empty quote response")

                // Persiste dans Room pour le QuoteWidget (offline-first) — transaction atomique
                val now = System.currentTimeMillis()
                quoteDao.upsertAndPurge(
                    quote.toEntity(syncedAt = now),
                    cutoffMillis = now - CacheTtl.QUOTES_MS,
                )

                quote
            }
        }

        // putIfAbsent retourne null si c'est nous le premier (gagnant) — sinon le Deferred
        // d'un autre appelant qui a gagné la course entre notre check et notre put.
        val current = inFlightQuotes.putIfAbsent(upperSymbol, deferred) ?: deferred
        if (current === deferred) {
            deferred.invokeOnCompletion { inFlightQuotes.remove(upperSymbol, deferred) }
            deferred.start()
        }

        return current.await()
    }

    override suspend fun getAvailableSymbols(): Result<List<String>> =
        getAvailableSymbols(search = null, limit = DEFAULT_SYMBOLS_PAGE_SIZE, offset = 0)
            .map { page -> page.items.map { it.ticker } }

    override suspend fun getAvailableSymbols(
        search: String?,
        limit: Int,
        offset: Int,
    ): Result<SymbolPage> = runCatchingCancellable {
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

    override suspend fun getHistory(symbol: String, limit: Int): Result<List<BigDecimal>> = runCatchingCancellable {
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
