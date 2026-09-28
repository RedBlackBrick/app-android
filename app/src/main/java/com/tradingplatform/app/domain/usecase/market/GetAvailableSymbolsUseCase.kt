package com.tradingplatform.app.domain.usecase.market

import com.tradingplatform.app.domain.model.SymbolPage
import com.tradingplatform.app.domain.repository.MarketDataRepository
import javax.inject.Inject

class GetAvailableSymbolsUseCase @Inject constructor(
    private val repository: MarketDataRepository,
) {
    /** Convenience overload — full ticker list, no search/pagination (widgets, legacy callers). */
    suspend operator fun invoke(): Result<List<String>> =
        repository.getAvailableSymbols()

    /** Server-side search + offset pagination — backs the watchlist symbol picker. */
    suspend operator fun invoke(
        search: String?,
        limit: Int = 100,
        offset: Int = 0,
    ): Result<SymbolPage> = repository.getAvailableSymbols(search, limit, offset)
}
