package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.NotificationApi
import com.tradingplatform.app.data.model.InboxNotificationDto
import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.InboxNotification
import com.tradingplatform.app.domain.repository.InboxRepository
import com.tradingplatform.app.domain.util.parseInstantOrNull
import com.tradingplatform.app.domain.util.runCatchingCancellable
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Boîte de réception serveur. Client Retrofit normal : `markRead` / `markAllRead` sont des POST
 * idempotents et non destructifs (pas de contrainte « jamais rejoué »). Aucun contenu de
 * notification n'est loggé.
 */
@Singleton
class InboxRepositoryImpl @Inject constructor(
    private val api: NotificationApi,
) : InboxRepository {

    override suspend fun list(limit: Int): Result<List<InboxNotification>> = runCatchingCancellable {
        val response = api.getNotifications(limit.coerceIn(MIN_LIMIT, MAX_LIMIT))
        if (!response.isSuccessful) throw HttpStatusException(response.code(), LIST_ENDPOINT)
        val dtos = response.body() ?: error("Empty notifications response")
        dtos.map { it.toDomain() }
    }

    override suspend fun unreadCount(): Result<Int> = runCatchingCancellable {
        val response = api.getUnreadCount()
        if (!response.isSuccessful) throw HttpStatusException(response.code(), UNREAD_COUNT_ENDPOINT)
        val dto = response.body() ?: error("Empty unread-count response")
        dto.unreadCount.coerceAtLeast(0)
    }

    override suspend fun markRead(id: String): Result<Unit> = runCatchingCancellable {
        val response = api.markNotificationRead(id)
        if (!response.isSuccessful) throw HttpStatusException(response.code(), MARK_READ_ENDPOINT)
        Unit
    }

    override suspend fun markAllRead(): Result<Unit> = runCatchingCancellable {
        val response = api.markAllNotificationsRead()
        if (!response.isSuccessful) throw HttpStatusException(response.code(), MARK_ALL_READ_ENDPOINT)
        Unit
    }

    private fun InboxNotificationDto.toDomain(): InboxNotification = InboxNotification(
        id = id,
        type = type,
        title = title,
        body = body.orEmpty(),
        read = read,
        createdAt = createdAt.parseInstantOrNull() ?: Instant.EPOCH,
    )

    private companion object {
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 200
        const val LIST_ENDPOINT = "v1/notifications"
        const val UNREAD_COUNT_ENDPOINT = "v1/notifications/unread-count"
        const val MARK_READ_ENDPOINT = "v1/notifications/{notification_id}/read"
        const val MARK_ALL_READ_ENDPOINT = "v1/notifications/read-all"
    }
}
