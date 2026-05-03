package com.tradingplatform.app.domain.model

/**
 * Mirror of the backend ``PortfolioCircuitBreakerStatus`` schema introduced
 * by ``GET /v1/risk/portfolios/{portfolio_id}/circuit-breaker/status``.
 *
 * The Dashboard uses [enabled] + [state] to choose between three visual
 * states: hidden (no rule active), green ("closed"), or red ("open"). When
 * [redisUnavailable] is true the badge should display a warning indicator
 * — the validation pipeline fails closed in that case, so the user is
 * effectively blocked even though the count is unknown.
 */
data class PortfolioCircuitBreakerStatus(
    val portfolioId: String,
    val enabled: Boolean,
    val state: CircuitBreakerState,
    val count: Int,
    val threshold: Int,
    val windowSeconds: Int,
    val ttlSeconds: Int?,
    val redisUnavailable: Boolean,
)

enum class CircuitBreakerState {
    CLOSED,
    OPEN,
    ;

    companion object {
        fun fromWire(value: String): CircuitBreakerState = when (value.lowercase()) {
            "open" -> OPEN
            else -> CLOSED
        }
    }
}
