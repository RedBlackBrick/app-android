package com.tradingplatform.app.domain.usecase.market

import com.tradingplatform.app.domain.repository.MarketDataRepository
import java.math.BigDecimal
import javax.inject.Inject

class GetSymbolHistoryUseCase @Inject constructor(
    private val repository: MarketDataRepository,
) {
    suspend operator fun invoke(symbol: String, limit: Int = 30): Result<List<BigDecimal>> =
        repository.getHistory(symbol, limit)
}
