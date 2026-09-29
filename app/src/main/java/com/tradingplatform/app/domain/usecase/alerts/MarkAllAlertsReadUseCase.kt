package com.tradingplatform.app.domain.usecase.alerts

import com.tradingplatform.app.domain.repository.AlertRepository
import javax.inject.Inject

/** Marque toutes les alertes locales comme lues (action « Tout lire » de l'écran Alertes). */
class MarkAllAlertsReadUseCase @Inject constructor(
    private val repository: AlertRepository,
) {
    suspend operator fun invoke(): Result<Unit> =
        repository.markAllRead()
}
