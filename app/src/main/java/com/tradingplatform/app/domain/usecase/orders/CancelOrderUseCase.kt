package com.tradingplatform.app.domain.usecase.orders

import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.repository.OrdersRepository
import javax.inject.Inject

/**
 * Demande l'annulation d'un ordre actif. Écriture : jamais rejouée ; le résultat
 * [WriteOutcome.REQUESTED_UNCONFIRMED] signifie « demandé, état à relire » (le backend convertit
 * plusieurs erreurs en 500). Un succès ne garantit rien côté broker.
 *
 * Le contrôle du contexte d'écriture (VPN, fraîcheur des données, biométrie) est fait en amont par
 * l'UI ; ce UseCase se contente de déléguer au [OrdersRepository].
 */
class CancelOrderUseCase @Inject constructor(
    private val repository: OrdersRepository,
) {
    suspend operator fun invoke(orderId: Long): Result<WriteOutcome> =
        repository.cancelOrder(orderId)
}
