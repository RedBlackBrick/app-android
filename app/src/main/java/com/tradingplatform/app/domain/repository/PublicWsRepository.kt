package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.Quote
import kotlinx.coroutines.flow.Flow

/**
 * Interface domain pour les flux de cours en temps réel via WebSocket public.
 *
 * Le canal public `/ws/public` est non authentifié. Il fournit des mises à jour
 * OHLCV + bid/ask en temps réel pour les symbols souscrits.
 *
 * Contrairement au canal privé ([WsRepository]), les données market sont
 * des updates live — pas d'historique, pas de cache Room dans ce repository.
 * La persistance Room reste gérée par [MarketDataRepository] (polling REST).
 *
 * Définie dans le domaine pour que les UseCases dépendent de cette abstraction
 * et non de l'implémentation data.
 */
interface PublicWsRepository {

    /**
     * Flow de cours en temps réel pour un symbol donné.
     *
     * - Active la subscription WS au symbol à la collecte.
     * - Annule la subscription au symbol quand le flow est annulé.
     * - Émet uniquement les [Quote] correspondant au [symbol] demandé.
     * - Ne complète jamais normalement — le caller annule via le scope parent.
     *
     * @param symbol Ticker à surveiller (ex: "AAPL"). Converti en uppercase par l'implémentation.
     */
    fun quoteUpdates(symbol: String): Flow<Quote>
}
