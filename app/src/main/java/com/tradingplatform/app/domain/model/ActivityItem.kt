package com.tradingplatform.app.domain.model

import java.time.Instant

/**
 * Represents a single item in the real-time activity feed on the Dashboard.
 *
 * Each subclass maps to a distinct WebSocket event type and carries the
 * relevant fields for display. The [timestamp] is set at reception time
 * (client-side [Instant.now]) since WS payloads do not carry a server timestamp.
 *
 * Pure Kotlin — no Android or data-layer dependencies.
 */
sealed class ActivityItem {
    abstract val timestamp: Instant

    /** An order has been filled or changed status. */
    data class OrderFilled(
        val orderId: String,
        val symbol: String,
        val side: String,
        val status: String,
        val quantity: Int?,
        override val timestamp: Instant,
    ) : ActivityItem()

    /** A strategy emitted a signal (informational — orders managed server-side). */
    data class Signal(
        val symbol: String,
        val action: String,
        val confidence: Double,
        val strategyType: String,
        override val timestamp: Instant,
    ) : ActivityItem()

    /** A risk or trading alert received via notification channel. */
    data class RiskAlert(
        val title: String,
        val body: String,
        val severity: String,
        override val timestamp: Instant,
    ) : ActivityItem()

    /**
     * Portfolio-level change, sent on every order execution.
     *
     * The backend event describes the trade that just executed ([symbol],
     * [side], [quantity], [price]) plus the resulting portfolio [totalValue] —
     * it does not carry a NAV/daily-P&L snapshot, so [dailyPnl] is null in
     * practice (kept nullable for forward compatibility).
     */
    data class PortfolioChange(
        val totalValue: Double?,
        val symbol: String?,
        val side: String?,
        val quantity: Double?,
        val price: Double?,
        val dailyPnl: Double?,
        override val timestamp: Instant,
    ) : ActivityItem()

    /** Catalyst event (earnings report, spinoff, dividend, etc.). */
    data class CatalystEvent(
        val symbol: String,
        val eventType: String,
        val title: String,
        override val timestamp: Instant,
    ) : ActivityItem()
}
