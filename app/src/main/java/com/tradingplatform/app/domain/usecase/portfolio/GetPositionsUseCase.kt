package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.domain.repository.PortfolioRepository
import javax.inject.Inject

class GetPositionsUseCase @Inject constructor(
    private val repository: PortfolioRepository,
) {
    suspend operator fun invoke(
        portfolioId: Int,
        status: PositionStatus = PositionStatus.OPEN,
    ): Result<List<Position>> =
        repository.getPositions(portfolioId, status)
}
