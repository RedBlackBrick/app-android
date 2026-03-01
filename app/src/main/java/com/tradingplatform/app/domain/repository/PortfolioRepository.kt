package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.domain.model.Transaction

interface PortfolioRepository {
    suspend fun getPositions(portfolioId: Int, status: PositionStatus): Result<List<Position>>
    suspend fun getPnl(portfolioId: Int, period: PnlPeriod): Result<PnlSummary>
    suspend fun getNav(portfolioId: Int): Result<NavSummary>
    suspend fun getTransactions(
        portfolioId: Int,
        limit: Int = 50,
        offset: Int = 0,
        symbol: String? = null,
    ): Result<List<Transaction>>
}
