package com.tradingplatform.app.domain.usecase.alerts

import com.tradingplatform.app.domain.model.Alert
import com.tradingplatform.app.domain.model.AlertType
import com.tradingplatform.app.domain.repository.AlertRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetFilteredAlertsUseCase @Inject constructor(
    private val repository: AlertRepository,
) {
    /**
     * Returns a reactive Flow of alerts filtered by the given [types].
     * Non-suspend — delegates to Room reactive query (offline-first).
     */
    operator fun invoke(types: Set<AlertType>): Flow<List<Alert>> =
        repository.getAlertsByTypes(types)
}
