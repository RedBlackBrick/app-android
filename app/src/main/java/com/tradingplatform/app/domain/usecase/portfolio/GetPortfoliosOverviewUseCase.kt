package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PortfolioOverviewItem
import com.tradingplatform.app.domain.repository.PortfolioRepository
import javax.inject.Inject

/**
 * « Mes portefeuilles » : valeur courante et P&L de la [PnlPeriod] pour tous les portefeuilles du
 * compte. `periodPnlPct` est une fraction (0.045 = 4,5 %).
 */
class GetPortfoliosOverviewUseCase @Inject constructor(
    private val repository: PortfolioRepository,
) {
    suspend operator fun invoke(period: PnlPeriod = PnlPeriod.DAY): Result<List<PortfolioOverviewItem>> =
        repository.getPortfoliosOverview(period)
}
