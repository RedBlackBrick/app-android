package com.tradingplatform.app.domain.usecase.alerts

import com.tradingplatform.app.domain.repository.InboxRepository
import javax.inject.Inject

/** Nombre de notifications serveur non lues (pastille de l'onglet Alertes). */
class GetInboxUnreadCountUseCase @Inject constructor(
    private val repository: InboxRepository,
) {
    suspend operator fun invoke(): Result<Int> = repository.unreadCount()
}
