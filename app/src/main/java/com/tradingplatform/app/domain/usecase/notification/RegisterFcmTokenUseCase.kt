package com.tradingplatform.app.domain.usecase.notification

import com.tradingplatform.app.domain.repository.NotificationRepository
import javax.inject.Inject

class RegisterFcmTokenUseCase @Inject constructor(
    private val repository: NotificationRepository,
) {
    suspend operator fun invoke(token: String, deviceFingerprint: String): Result<Unit> =
        repository.registerFcmToken(token, deviceFingerprint)
}
