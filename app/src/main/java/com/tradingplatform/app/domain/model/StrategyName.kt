package com.tradingplatform.app.domain.model

/**
 * Nom d'une stratégie du catalogue (`GET /v1/strategies`), joint aux liens portefeuille-stratégie
 * par [id] (= `strategy_id` du lien).
 *
 * Pure Kotlin domain model, no Android or Retrofit dependencies.
 */
data class StrategyName(
    val id: String,
    val name: String,
)
