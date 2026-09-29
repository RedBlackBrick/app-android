package com.tradingplatform.app.data.api

import com.tradingplatform.app.data.model.FcmTokenRequestDto
import com.tradingplatform.app.data.model.FcmTokenResponseDto
import com.tradingplatform.app.data.model.InboxNotificationDto
import com.tradingplatform.app.data.model.UnreadCountDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface NotificationApi {
    @POST("v1/notifications/fcm-token")
    suspend fun registerFcmToken(@Body request: FcmTokenRequestDto): Response<FcmTokenResponseDto>

    /** Tableau JSON nu (pas d'enveloppe, pas de total), tri `created_at DESC`. `limit` : 1..200. */
    @GET("v1/notifications")
    suspend fun getNotifications(@Query("limit") limit: Int = 50): Response<List<InboxNotificationDto>>

    @GET("v1/notifications/unread-count")
    suspend fun getUnreadCount(): Response<UnreadCountDto>

    /** 204 sans corps ; idempotent. */
    @POST("v1/notifications/{notification_id}/read")
    suspend fun markNotificationRead(@Path("notification_id") notificationId: String): Response<Unit>

    /** 204 sans corps ; marque aussi les enfants de digest masqués. */
    @POST("v1/notifications/read-all")
    suspend fun markAllNotificationsRead(): Response<Unit>
}
