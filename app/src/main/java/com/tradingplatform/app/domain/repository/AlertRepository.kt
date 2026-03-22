package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.Alert
import com.tradingplatform.app.domain.model.AlertType
import kotlinx.coroutines.flow.Flow

interface AlertRepository {
    fun getAlerts(): Flow<List<Alert>>
    fun getAlertsByTypes(types: Set<AlertType>): Flow<List<Alert>>
    suspend fun markRead(alertId: Long): Result<Unit>
    suspend fun insertAlert(alert: Alert): Result<Unit>
    suspend fun purgeExpired(): Result<Unit>
}
