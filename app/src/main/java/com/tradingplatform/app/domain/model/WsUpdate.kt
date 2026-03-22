package com.tradingplatform.app.domain.model

/**
 * Domain-layer representations of real-time WebSocket updates.
 *
 * These are pure Kotlin classes with no Android or data-layer dependencies.
 * The data layer maps [com.tradingplatform.app.data.websocket.WsEvent] subtypes
 * to these domain models inside [WsRepositoryImpl].
 *
 * The raw JSONObject payload is deserialized into typed fields here so that
 * UseCases and ViewModels never manipulate raw JSON.
 */
sealed class WsUpdate {

    /**
     * Real-time portfolio-level update (NAV, global P&L).
     *
     * Fields are nullable to tolerate partial server payloads — the ViewModel
     * uses the non-null fields to update its state and ignores nulls.
     */
    data class PortfolioUpdate(
        val portfolioId: String? = null,
        val nav: Double? = null,
        val dailyPnl: Double? = null,
        val totalPnl: Double? = null,
    ) : WsUpdate()

    /** Real-time position-level update (single position changed). */
    data class PositionUpdate(
        val positionId: String? = null,
        val symbol: String? = null,
        val unrealizedPnl: Double? = null,
        val currentPrice: Double? = null,
    ) : WsUpdate()

    /** User-facing notification received via the private WebSocket channel. */
    data class Notification(
        val notifType: String,
        val title: String,
        val body: String,
    ) : WsUpdate()

    /** Real-time order update (status change, fill event). */
    data class OrderUpdate(
        val orderId: String? = null,
        val symbol: String? = null,
        val side: String? = null,
        val status: String? = null,
        val quantity: Int? = null,
        val fillPrice: Double? = null,
    ) : WsUpdate()

    /** Real-time strategy signal (informational — orders managed server-side). */
    data class StrategySignal(
        val signalId: String? = null,
        val strategyId: String? = null,
        val symbol: String? = null,
        val action: String? = null,
        val confidence: Double? = null,
        val strategyType: String? = null,
    ) : WsUpdate()
}
