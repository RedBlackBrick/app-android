package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.model.WsUpdate
import com.tradingplatform.app.domain.repository.WsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Expose le flux temps réel des mises à jour de portfolio via le WebSocket privé.
 *
 * Ce UseCase sert d'intermédiaire obligatoire entre [DashboardViewModel] et
 * [WsRepository] pour respecter la règle architecture : ViewModel → UseCase → Repository.
 *
 * Aucune logique métier ici — le flux est propagé tel quel.
 * Le ViewModel décide quoi faire à chaque [WsUpdate.PortfolioUpdate] reçu.
 */
class GetPortfolioWsUpdatesUseCase @Inject constructor(
    private val wsRepository: WsRepository,
) {
    operator fun invoke(): Flow<WsUpdate.PortfolioUpdate> = wsRepository.portfolioUpdates
}
