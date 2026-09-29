package com.tradingplatform.app.domain.model

/**
 * Ligne « stratégie » d'un portefeuille : un lien portefeuille-stratégie ([isActive] est le statut
 * du LIEN, distinct du statut global de la stratégie) enrichi de son nom.
 *
 * [name] vaut `null` quand le catalogue ne connaît pas la stratégie (supprimée, ou privée d'un
 * autre utilisateur — contrat backend §9.8) ou n'a pas pu être lu : l'UI affiche alors un libellé
 * neutre, l'action pause / reprise reste possible via [strategyId].
 *
 * Attention : côté backend un lien détaché sur le web reste listé avec `is_active = false`
 * (soft delete), indiscernable d'un lien simplement mis en pause.
 *
 * Pure Kotlin domain model, no Android or Retrofit dependencies.
 */
data class PortfolioStrategyEntry(
    val strategyId: String,
    val name: String?,
    val isActive: Boolean,
)
