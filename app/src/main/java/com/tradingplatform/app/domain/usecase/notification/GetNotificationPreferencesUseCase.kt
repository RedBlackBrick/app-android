package com.tradingplatform.app.domain.usecase.notification

import com.tradingplatform.app.domain.model.NotificationPreferences
import com.tradingplatform.app.domain.repository.NotificationPreferencesRepository
import javax.inject.Inject

/** Préférences de notification du compte (canaux par catégorie, heures calmes, seuils). */
class GetNotificationPreferencesUseCase @Inject constructor(
    private val repository: NotificationPreferencesRepository,
) {
    suspend operator fun invoke(): Result<NotificationPreferences> = repository.get()
}
