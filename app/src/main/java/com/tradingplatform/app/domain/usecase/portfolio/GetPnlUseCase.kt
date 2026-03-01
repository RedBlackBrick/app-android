package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.repository.PortfolioRepository
import javax.inject.Inject

class GetPnlUseCase @Inject constructor(
    private val repository: PortfolioRepository,
) {
    suspend operator fun invoke(
        portfolioId: String,
        period: PnlPeriod = PnlPeriod.DAY,
    ): Result<PnlSummary> =
        repository.getPnl(portfolioId, period)
}
