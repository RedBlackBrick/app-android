package com.tradingplatform.app.domain.usecase.alerts

import com.tradingplatform.app.domain.repository.AlertRepository
import javax.inject.Inject

class MarkAlertReadUseCase @Inject constructor(
    private val repository: AlertRepository,
) {
    suspend operator fun invoke(alertId: Long): Result<Unit> =
        repository.markRead(alertId)
}
