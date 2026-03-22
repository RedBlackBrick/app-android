package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.model.WsConnectionState
import com.tradingplatform.app.domain.repository.WsRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Expose l'etat de connexion du WebSocket prive a la couche UI (F5).
 *
 * Intermediaire obligatoire entre [DashboardViewModel] et [WsRepository]
 * pour respecter la regle architecture : ViewModel -> UseCase -> Repository.
 *
 * Aucune logique metier ici — le StateFlow est propage tel quel.
 * Le ViewModel applique le debounce de 2s pour eviter le flicker.
 */
class GetWsConnectionStateUseCase @Inject constructor(
    private val wsRepository: WsRepository,
) {
    operator fun invoke(): StateFlow<WsConnectionState> = wsRepository.connectionState
}
