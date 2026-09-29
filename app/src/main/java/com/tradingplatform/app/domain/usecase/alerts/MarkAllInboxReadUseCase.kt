package com.tradingplatform.app.domain.usecase.alerts

import com.tradingplatform.app.domain.repository.InboxRepository
import javax.inject.Inject

/** Marque toutes les notifications serveur comme lues (idempotent). */
class MarkAllInboxReadUseCase @Inject constructor(
    private val repository: InboxRepository,
) {
    suspend operator fun invoke(): Result<Unit> = repository.markAllRead()
}
