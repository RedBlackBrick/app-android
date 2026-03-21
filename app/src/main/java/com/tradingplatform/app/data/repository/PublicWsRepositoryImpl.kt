package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.websocket.PublicWsClient
import com.tradingplatform.app.data.websocket.PublicWsEvent
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.repository.PublicWsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import java.math.BigDecimal
import javax.inject.Inject

/**
 * Implémentation de [PublicWsRepository] basée sur [PublicWsClient].
 *
 * Gère le cycle de vie des subscriptions WS :
 * - Subscribe au symbol à la collecte du flow (via [onStart]).
 * - Unsubscribe au symbol à l'annulation du flow (via [onCompletion]).
 *
 * La conversion [PublicWsEvent.MarketData] → [Quote] est faite ici.
 * Les champs `change` et `changePercent` ne sont pas fournis par le WS public —
 * ils sont mis à 0 (données uniquement disponibles via l'endpoint REST).
 * Le champ `source` est `"ws_public"` pour distinguer l'origine dans les logs.
 *
 * Le scope @Singleton est géré par [com.tradingplatform.app.di.WebSocketModule.providePublicWsRepository].
 */
class PublicWsRepositoryImpl @Inject constructor(
    private val wsClient: PublicWsClient,
) : PublicWsRepository {

    @OptIn(FlowPreview::class)
    override fun quoteUpdates(symbol: String): Flow<Quote> {
        val upper = symbol.uppercase()
        return wsClient.events
            .filterIsInstance<PublicWsEvent.MarketData>()
            .filter { it.symbol == upper }
            .map { event -> event.toQuote() }
            .sample(QUOTE_SAMPLE_INTERVAL_MS)
            .onStart { wsClient.subscribe(upper) }
            .onCompletion { wsClient.unsubscribe(upper) }
    }

    companion object {
        private const val QUOTE_SAMPLE_INTERVAL_MS = 250L
    }

    // ── Conversion MarketData → Quote ──────────────────────────────────────────

    /**
     * Convertit un [PublicWsEvent.MarketData] en [Quote].
     *
     * - `price` : champ `price` du stream (= close ou mid selon la source).
     * - `bid` / `ask` : utilisent la valeur du stream si disponible, sinon `price`.
     * - `change` / `changePercent` : non disponibles en WS public — mis à 0.
     * - `source` : `"ws_public"` pour traçabilité.
     */
    private fun PublicWsEvent.MarketData.toQuote(): Quote = Quote(
        symbol = symbol,
        price = price,
        bid = bid ?: price,
        ask = ask ?: price,
        volume = volume,
        change = BigDecimal.ZERO,
        changePercent = 0.0,
        timestamp = timestamp,
        source = "ws_public",
    )
}
