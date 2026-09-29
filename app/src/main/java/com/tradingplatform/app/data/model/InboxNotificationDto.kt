package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Élément de `GET /v1/notifications` (backend `NotificationResponse`). Attention aux noms REST :
 * `type` (pas `notification_type`, réservé au WS), `body` (pas `message`), `read` (pas `is_read`).
 * `data`, `priority`, `user_id`, `is_digested`… ne sont pas consommés par le mobile.
 * `title` / `body` / `created_at` sont nullables par prudence (le backend type `body` et
 * `created_at` en `| None`).
 */
@JsonClass(generateAdapter = true)
data class InboxNotificationDto(
    @Json(name = "id") val id: String,
    @Json(name = "type") val type: String,
    @Json(name = "title") val title: String? = null,
    @Json(name = "body") val body: String? = null,
    @Json(name = "read") val read: Boolean = false,
    @Json(name = "created_at") val createdAt: String? = null,
)

/** `GET /v1/notifications/unread-count` (backend `UnreadCountResponse`). */
@JsonClass(generateAdapter = true)
data class UnreadCountDto(
    @Json(name = "unread_count") val unreadCount: Int = 0,
)
