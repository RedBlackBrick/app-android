package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.PortfolioCircuitBreakerStatus
import com.tradingplatform.app.domain.model.RiskStatus
import com.tradingplatform.app.domain.model.WriteOutcome

interface RiskRepository {
    suspend fun getPortfolioCircuitBreakerStatus(
        portfolioId: String,
    ): Result<PortfolioCircuitBreakerStatus>

    /**
     * Situation de risque d'un portefeuille (kill switch, violations non résolues, perte
     * journalière vs limite, drawdown). Agrégat tolérant : une lecture qui échoue dégrade son champ
     * (`RiskStatus.isPartial = true`) ; `failure` seulement si TOUTES les lectures échouent.
     */
    suspend fun getRiskStatus(portfolioId: String): Result<RiskStatus>

    /**
     * Active le kill switch du portefeuille (`POST /v1/risk/portfolios/{id}/kill-switch`).
     *
     * **Activation SEULE** : la levée (`DELETE`) se fait sur le web, jamais depuis le mobile. Un kill
     * switch bloque les nouveaux ordres mais n'annule aucun ordre ouvert. Écriture jamais rejouée :
     *
     * - 2xx → `success(CONFIRMED)` ;
     * - 5xx ou timeout / IOException après envoi → `success(REQUESTED_UNCONFIRMED)` (l'appelant relit
     *   avec [getRiskStatus]) ;
     * - [reason] vide, VPN absent, 4xx → `failure`.
     */
    suspend fun activatePortfolioKillSwitch(portfolioId: String, reason: String): Result<WriteOutcome>
}
