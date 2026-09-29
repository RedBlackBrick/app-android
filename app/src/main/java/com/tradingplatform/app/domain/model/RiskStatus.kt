package com.tradingplatform.app.domain.model

/**
 * Situation de risque d'UN portefeuille, agrégée depuis 3 lectures backend
 * (`kill-switch/active`, `risk-360-summary`, `violations`). Chaque lecture qui échoue dégrade son
 * champ (faux / `null` / 0) sans faire échouer l'ensemble ; [isPartial] vaut alors `true` (l'UI
 * peut signaler « données partielles » plutôt que d'afficher un faux « tout va bien »).
 *
 * - [killSwitchActive] : kill switch du portefeuille OU global actif (les deux bloquent les nouveaux
 *   ordres). Les kill switches de stratégie ou de symbole ne comptent pas.
 * - [killSwitchReason] : motif du kill switch (texte libre du serveur, y compris pour un kill switch
 *   posé par le système), `null` s'il est inconnu.
 * - [unresolvedViolations] : nombre de violations non résolues (plafonné à 100 côté requête).
 * - [dailyLossUsagePct] : FRACTION (1.0 = limite de perte journalière atteinte, peut dépasser 1) =
 *   perte du jour (`max(0, -daily_pnl)`) / limite. `null` si aucune limite configurée ou donnée
 *   indisponible. Approximatif si le portefeuille n'est pas en USD (limite en USD, P&L en devise du
 *   portefeuille).
 * - [drawdownCurrentPct] : FRACTION négative ou nulle (-0.124 = -12,4 %), `null` si inconnue.
 */
data class RiskStatus(
    val killSwitchActive: Boolean,
    val killSwitchReason: String?,
    val unresolvedViolations: Int,
    val dailyLossUsagePct: Double?,
    val drawdownCurrentPct: Double?,
    val isPartial: Boolean = false,
)
