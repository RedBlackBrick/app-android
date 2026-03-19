package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class FcmTokenRequestDto(
    @Json(name = "fcm_token") val fcmToken: String,
    @Json(name = "device_fingerprint") val deviceFingerprint: String,
)

@JsonClass(generateAdapter = true)
data class FcmTokenResponseDto(
    @Json(name = "registered") val registered: Boolean,
)
