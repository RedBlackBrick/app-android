package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.model.WsConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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
     * - Subscriptions ref-comptées : plusieurs collecteurs du même symbol partagent une
     *   seule subscription serveur, retirée seulement quand le dernier collecteur s'arrête.
     * - Émet uniquement les [Quote] correspondant au [symbol] demandé.
     * - Ne complète jamais normalement — le caller annule via le scope parent.
     * - Ne lève pas d'erreur sur perte de connexion : le flow reste actif et reprend après
     *   la reconnexion automatique. Utiliser [connectionState] pour détecter la coupure et
     *   basculer en fallback REST.
     *
     * @param symbol Ticker à surveiller (ex: "AAPL"). Converti en uppercase par l'implémentation.
     */
    fun quoteUpdates(symbol: String): Flow<Quote>

    /**
     * État de la connexion WS publique. Toute valeur autre que
     * [WsConnectionState.Connected] signifie « pas de cours live ».
     */
    val connectionState: StateFlow<WsConnectionState>

    /**
     * True quand l'application est au premier plan (ProcessLifecycleOwner) — permet au
     * fallback REST de ne pas poller en arrière-plan.
     */
    val isAppForeground: StateFlow<Boolean>
}
