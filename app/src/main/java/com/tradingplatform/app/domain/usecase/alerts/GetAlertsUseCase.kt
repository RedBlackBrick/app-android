package com.tradingplatform.app.domain.usecase.alerts

import com.tradingplatform.app.domain.model.Alert
import com.tradingplatform.app.domain.repository.AlertRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetAlertsUseCase @Inject constructor(
    private val repository: AlertRepository,
) {
    // Non-suspend — retourne un Flow pour observation Room réactive (offline-first)
    operator fun invoke(): Flow<List<Alert>> = repository.getAlerts()
}
