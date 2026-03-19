package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.NotificationApi
import com.tradingplatform.app.data.model.FcmTokenRequestDto
import com.tradingplatform.app.domain.repository.NotificationRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepositoryImpl @Inject constructor(
    private val notificationApi: NotificationApi,
) : NotificationRepository {

    override suspend fun registerFcmToken(token: String, deviceFingerprint: String): Result<Unit> =
        runCatching {
            val response = notificationApi.registerFcmToken(
                FcmTokenRequestDto(
                    fcmToken = token,
                    deviceFingerprint = deviceFingerprint,
                )
            )
            if (!response.isSuccessful) {
                error("FCM token registration failed: HTTP ${response.code()}")
            }
            Timber.d("NotificationRepository: FCM token registered successfully")
        }
}
