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
     * Real-time portfolio-level update, sent on every order execution
     * (`app/portfolio/consumer.py` `ws_payload`, built ~L2151-2160).
     *
     * The backend does not emit a NAV/daily-P&L snapshot on this channel — the
     * payload instead describes the trade that just executed (symbol, side,
     * quantity, price) plus the resulting portfolio totals. [nav] is kept for
     * backward compatibility with existing consumers and is populated from
     * [totalValue]. [dailyPnl]/[totalPnl] stay null since the backend never
     * sends them on this event.
     *
     * Fields are nullable to tolerate partial server payloads — the ViewModel
     * uses the non-null fields to update its state and ignores nulls.
     */
    data class PortfolioUpdate(
        val portfolioId: String? = null,
        val symbol: String? = null,
        val side: String? = null,
        val quantity: Double? = null,
        val price: Double? = null,
        val totalValue: Double? = null,
        val cashBalance: Double? = null,
        val positionsValue: Double? = null,
        val nav: Double? = null,
        val dailyPnl: Double? = null,
        val totalPnl: Double? = null,
    ) : WsUpdate()

    /**
     * Real-time position-level update (single position changed), sent on every
     * order execution (`app/portfolio/consumer.py` `ws_position_payload`, built
     * ~L2164-2199).
     *
     * The backend does not currently send a `position_id` — [positionId] stays
     * nullable so a future backend addition can populate it without an API
     * change here. [isActive] defaults to `true` (absent on payloads predating
     * this field) — `false` means the position was fully closed by this fill.
     */
    data class PositionUpdate(
        val positionId: String? = null,
        val symbol: String? = null,
        val side: String? = null,
        val quantity: Double? = null,
        val averagePrice: Double? = null,
        val lastPrice: Double? = null,
        val unrealizedPnl: Double? = null,
        val realizedPnl: Double? = null,
        val isActive: Boolean = true,
        /** Portefeuille de la position (`portfolio_id` du payload WS) ; null si absent. */
        val portfolioId: String? = null,
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

    /**
     * Catalyst event (earnings, spinoff, etc.).
     *
     * PR-5c FINDING / PR-2.5 fix: the backend (`app/events/catalyst/consumer.py`
     * `_forward_to_websocket`) sends `catalyst_type` (never `event_type`) and no
     * top-level `title`/`description` — those don't exist on this channel. The
     * envelope carries a nested `data` object whose shape depends on
     * [catalystType] (`EarningsEventData` / `SpinoffEventData` in
     * `app/events/catalyst/schemas.py`, neither of which has a title/description
     * field either). [description] is therefore synthesized by
     * `WsRepository.catalystEvents` from whichever of those nested fields exist
     * for the given [catalystType] — it stays null when nothing usable is found.
     */
    data class CatalystEvent(
        val symbol: String? = null,
        val catalystType: String? = null,
        val strategyId: String? = null,
        val description: String? = null,
    ) : WsUpdate()
}
