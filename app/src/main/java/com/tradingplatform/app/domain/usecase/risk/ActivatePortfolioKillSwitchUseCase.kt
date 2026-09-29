package com.tradingplatform.app.domain.usecase.risk

import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.repository.RiskRepository
import javax.inject.Inject

/**
 * Active le kill switch d'un portefeuille (bloque les nouveaux ordres ; n'annule aucun ordre
 * ouvert). **Activation seule** : il n'existe volontairement aucun cas d'usage de désactivation
 * dans l'app (levée = web).
 *
 * Règle métier : un motif est obligatoire (1 à [MAX_REASON_LENGTH] caractères après `trim`). Un
 * motif invalide donne `failure(IllegalArgumentException)` AVANT tout appel réseau. Jamais de retry :
 * `REQUESTED_UNCONFIRMED` signifie « demandé » — l'appelant relit l'état, ne rejoue pas.
 */
class ActivatePortfolioKillSwitchUseCase @Inject constructor(
    private val repository: RiskRepository,
) {
    suspend operator fun invoke(portfolioId: String, reason: String): Result<WriteOutcome> {
        val trimmed = reason.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Le motif du kill switch est obligatoire"))
        }
        if (trimmed.length > MAX_REASON_LENGTH) {
            return Result.failure(
                IllegalArgumentException("Le motif du kill switch est limité à $MAX_REASON_LENGTH caractères"),
            )
        }
        return repository.activatePortfolioKillSwitch(portfolioId, trimmed)
    }

    companion object {
        /** Limite backend de `PortfolioKillSwitchActivate.reason`. */
        const val MAX_REASON_LENGTH = 500
    }
}
