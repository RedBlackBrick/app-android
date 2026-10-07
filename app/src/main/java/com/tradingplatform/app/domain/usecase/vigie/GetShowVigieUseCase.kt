package com.tradingplatform.app.domain.usecase.vigie

import com.tradingplatform.app.domain.repository.NotificationPreferencesRepository
import javax.inject.Inject

/**
 * Réglage de compte `ui.appearance.showVigie` (lecture seule ; absent → `false`). Lu via le
 * repository de préférences existant — pas de second repository.
 */
class GetShowVigieUseCase @Inject constructor(
    private val repository: NotificationPreferencesRepository,
) {
    suspend operator fun invoke(): Result<Boolean> = repository.get().map { it.showVigie }
}
