package com.tradingplatform.app.data.websocket

import java.math.BigDecimal
import java.time.Instant

/**
 * Événements émis par [PublicWsClient] sur le canal public `/ws/public`.
 *
 * Types serveur connus (cf. TP2 README.md §Message Types) :
 *   market_data, subscription_ack, ping, error
 */
sealed class PublicWsEvent {

    /**
     * Mise à jour de cours en temps réel pour un symbol.
     *
     * Champs fournis par le Redis Stream `clean-market-data` via MarketDataBridge.
     * `change` et `changePercent` ne sont pas émis par le WS public — ils sont
     * calculés à partir du cours REST (non disponible en streaming). Valeur : 0.
     */
    data class MarketData(
        val symbol: String,
        val price: BigDecimal,
        val open: BigDecimal,
        val high: BigDecimal,
        val low: BigDecimal,
        val close: BigDecimal,
        val volume: Long,
        val bid: BigDecimal?,
        val ask: BigDecimal?,
        val timestamp: Instant,
        val sourceName: String? = null,
        val sourceType: String? = null,
        val quality: Int? = null,
        val dataMode: String? = null,
    ) : PublicWsEvent()

    /**
     * Acquittement de subscribe ou unsubscribe.
     * Ignoré par le repository — sert uniquement au debug.
     */
    data class SubscriptionAck(
        val action: String,
        val symbols: List<String>,
    ) : PublicWsEvent()

    /** Connexion établie. */
    data object Connected : PublicWsEvent()

    /** Connexion fermée (normale ou suite à une erreur). */
    data class Disconnected(val reason: String?) : PublicWsEvent()
}
