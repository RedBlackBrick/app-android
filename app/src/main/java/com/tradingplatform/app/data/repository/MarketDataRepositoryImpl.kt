package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.MarketDataApi
import com.tradingplatform.app.data.local.db.dao.QuoteDao
import com.tradingplatform.app.data.model.toDomain
import com.tradingplatform.app.data.model.toEntity
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.repository.MarketDataRepository
import java.math.BigDecimal
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

    override suspend fun getAvailableSymbols(): Result<List<String>> = runCatching {
        val response = marketDataApi.getSymbols()
        if (!response.isSuccessful) {
            error("Get symbols failed: HTTP ${response.code()}")
        }
        response.body() ?: emptyList()
    }

    override suspend fun getHistory(symbol: String, limit: Int): Result<List<BigDecimal>> = runCatching {
        val response = marketDataApi.getHistory(symbol.uppercase(), limit = limit)
        if (!response.isSuccessful) {
            error("Get history failed: HTTP ${response.code()}")
        }
        response.body()?.map { it.close } ?: emptyList()
    }
}
