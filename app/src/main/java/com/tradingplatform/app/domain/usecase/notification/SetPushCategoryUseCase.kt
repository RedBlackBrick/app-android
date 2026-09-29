package com.tradingplatform.app.domain.usecase.notification

import com.tradingplatform.app.domain.model.NotifCategory
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.repository.NotificationPreferencesRepository
import javax.inject.Inject

/**
 * Active / coupe le push d'une catégorie de notification (read-modify-write côté repository).
 * Écriture jamais rejouée : `REQUESTED_UNCONFIRMED` = « demandé », l'appelant relit les préférences.
 */
class SetPushCategoryUseCase @Inject constructor(
    private val repository: NotificationPreferencesRepository,
) {
    suspend operator fun invoke(category: NotifCategory, enabled: Boolean): Result<WriteOutcome> =
        repository.setPushEnabled(category, enabled)
}
