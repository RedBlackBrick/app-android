package com.tradingplatform.app.domain.usecase.market

import com.tradingplatform.app.domain.repository.WatchlistRepository
import javax.inject.Inject

class RemoveFromWatchlistUseCase @Inject constructor(
    private val repository: WatchlistRepository,
) {
    suspend operator fun invoke(symbol: String): Result<Unit> =
        repository.removeSymbol(symbol)
}
