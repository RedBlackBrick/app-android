package com.tradingplatform.app.domain.usecase.alerts

import com.tradingplatform.app.domain.model.InboxNotification
import com.tradingplatform.app.domain.repository.InboxRepository
import javax.inject.Inject

/** Notifications serveur les plus récentes (boîte de réception partagée avec le web). */
class GetInboxUseCase @Inject constructor(
    private val repository: InboxRepository,
) {
    suspend operator fun invoke(limit: Int = DEFAULT_LIMIT): Result<List<InboxNotification>> =
        repository.list(limit)

    private companion object {
        const val DEFAULT_LIMIT = 50
    }
}
