package com.tradingplatform.app.domain.repository

interface NotificationRepository {
    suspend fun registerFcmToken(token: String, deviceFingerprint: String): Result<Unit>
}
