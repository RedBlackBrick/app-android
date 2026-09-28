package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.NotificationApi
import com.tradingplatform.app.data.model.FcmTokenRequestDto
import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.repository.NotificationRepository
import com.tradingplatform.app.domain.util.runCatchingCancellable
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepositoryImpl @Inject constructor(
    private val notificationApi: NotificationApi,
) : NotificationRepository {

    override suspend fun registerFcmToken(token: String, deviceFingerprint: String): Result<Unit> =
        runCatchingCancellable {
            val response = notificationApi.registerFcmToken(
                FcmTokenRequestDto(
                    fcmToken = token,
                    deviceFingerprint = deviceFingerprint,
                )
            )
            if (!response.isSuccessful) {
                // Wrapped by runCatchingCancellable — HttpStatusException.isRetryable lets the caller
                // (FcmTokenRegistrationWorker) distinguish a transient 5xx/429/408 (retry) from
                // a definitive 4xx like a malformed token (failure, no point retrying).
                throw HttpStatusException(response.code(), ENDPOINT)
            }
            Timber.d("NotificationRepository: FCM token registered successfully")
        }

    private companion object {
        const val ENDPOINT = "v1/notifications/fcm-token"
    }
}
