package com.tradingplatform.app.domain.model

/**
 * Subset of the backend ``PortfolioStrategyLink`` schema needed by the
 * mobile app — we only care about the link's active state and the strategy
 * id (used to compute the "stratégies actives" count tile on the Dashboard).
 *
 * Pure Kotlin domain model, no Android or Retrofit dependencies.
 */
data class PortfolioStrategyLink(
    val portfolioId: String,
    val strategyId: String,
    val isActive: Boolean,
)
