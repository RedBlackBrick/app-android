package com.tradingplatform.app.domain.usecase.alerts

import com.tradingplatform.app.domain.repository.InboxRepository
import javax.inject.Inject

/** Marque une notification serveur comme lue (idempotent). */
class MarkInboxReadUseCase @Inject constructor(
    private val repository: InboxRepository,
) {
    suspend operator fun invoke(id: String): Result<Unit> = repository.markRead(id)
}
