package com.tradingplatform.app.data.api

import com.tradingplatform.app.data.model.FcmTokenRequestDto
import com.tradingplatform.app.data.model.FcmTokenResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface NotificationApi {
    @POST("v1/notifications/fcm-token")
    suspend fun registerFcmToken(@Body request: FcmTokenRequestDto): Response<FcmTokenResponseDto>
}
