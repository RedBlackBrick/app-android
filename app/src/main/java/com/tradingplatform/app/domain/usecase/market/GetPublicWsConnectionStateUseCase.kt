package com.tradingplatform.app.domain.usecase.market

import com.tradingplatform.app.domain.model.WsConnectionState
import com.tradingplatform.app.domain.repository.PublicWsRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Expose l'état de connexion du WebSocket public (cours temps réel) à la couche UI.
 *
 * Miroir de [com.tradingplatform.app.domain.usecase.portfolio.GetWsConnectionStateUseCase]
 * pour le canal privé : intermédiaire obligatoire ViewModel → UseCase → Repository.
 * Aucune logique métier — les StateFlow sont propagés tels quels ; le debounce anti-flicker
 * est appliqué par `QuoteFallbackController` côté UI.
 *
 * Consommé par `MarketDataViewModel` et `DashboardViewModel` pour piloter le fallback REST :
 * polling uniquement quand l'état n'est pas [WsConnectionState.Connected] **et** que l'app
 * est au premier plan ([isAppForeground]).
 */
class GetPublicWsConnectionStateUseCase @Inject constructor(
    private val repository: PublicWsRepository,
) {
    /** État de la connexion WS publique. */
    operator fun invoke(): StateFlow<WsConnectionState> = repository.connectionState

    /** True quand l'app est au premier plan — le fallback REST ne polle pas en arrière-plan. */
    fun isAppForeground(): StateFlow<Boolean> = repository.isAppForeground
}
