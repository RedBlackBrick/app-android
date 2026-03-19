package com.tradingplatform.app.domain.usecase.market

import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.repository.PublicWsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Expose un [Flow] de cours en temps réel pour un symbol via le WebSocket public.
 *
 * Le flow est actif tant que le collecteur est actif. La subscription WS est
 * établie à la collecte et annulée à l'annulation du flow.
 *
 * À utiliser dans [DashboardViewModel] en remplacement du polling REST 30s.
 * Le polling REST reste actif en fallback si le WS est indisponible.
 */
class GetQuoteStreamUseCase @Inject constructor(
    private val repository: PublicWsRepository,
) {
    operator fun invoke(symbol: String): Flow<Quote> =
        repository.quoteUpdates(symbol)
}
