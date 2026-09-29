package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.Portfolio
import kotlinx.coroutines.flow.StateFlow

/**
 * Source unique de vérité du **portefeuille actif** (compte multi-portefeuille).
 *
 * Le compte peut posséder N portefeuilles ; l'utilisateur en sélectionne un, qui pilote tous
 * les écrans (Positions, Ordres, Historique, Performance…). Les widgets et le Worker relisent
 * la même sélection via la clé DataStore `PORTFOLIO_ID`, écrite ici.
 *
 * Room v7 n'a pas de colonne `portfolio_id` : à chaque **changement** de portefeuille actif, les
 * caches portfolio-scopés (`positions`, `pnl_snapshots`) sont purgés avant d'émettre le nouvel id.
 */
interface PortfolioSelectionRepository {

    /** Dernière liste connue des portefeuilles du compte (vide avant le premier [refresh]). */
    val portfolios: StateFlow<List<Portfolio>>

    /**
     * Id du portefeuille actif ; `null` tant qu'inconnu. Initialisé de façon asynchrone depuis
     * le stockage chiffré au démarrage, puis validé contre la liste au premier [refresh].
     */
    val activePortfolioId: StateFlow<String?>

    /**
     * `GET /v1/portfolios`. Conserve la sélection persistée si elle existe encore dans la liste,
     * sinon retombe sur le premier portefeuille ; persiste la sélection ; purge les caches
     * portfolio-scopés si l'id actif change.
     *
     * Liste vide => `Result.failure(IllegalStateException("No portfolio found"))` (état serveur
     * incohérent : un compte a toujours au moins un portefeuille).
     */
    suspend fun refresh(): Result<List<Portfolio>>

    /**
     * Sélectionne [portfolioId] comme portefeuille actif. Échec si l'id est absent de la liste
     * connue. Sélectionner l'id déjà actif est un no-op (aucune purge). Sinon : purge
     * `positions` + `pnl_snapshots`, persiste `PORTFOLIO_ID`, puis émet le nouvel id.
     */
    suspend fun select(portfolioId: String): Result<Unit>
}
