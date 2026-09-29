package com.tradingplatform.app.domain.usecase.risk

import com.tradingplatform.app.domain.model.RiskStatus
import com.tradingplatform.app.domain.repository.RiskRepository
import javax.inject.Inject

/**
 * Situation de risque d'un portefeuille (kill switch, violations non résolues, perte journalière,
 * drawdown). Agrégat tolérant : voir [RiskRepository.getRiskStatus] et `RiskStatus.isPartial`.
 */
class GetRiskStatusUseCase @Inject constructor(
    private val repository: RiskRepository,
) {
    suspend operator fun invoke(portfolioId: String): Result<RiskStatus> =
        repository.getRiskStatus(portfolioId)
}
