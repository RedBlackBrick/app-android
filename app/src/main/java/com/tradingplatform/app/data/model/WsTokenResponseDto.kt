package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class WsTokenResponseDto(
    @Json(name = "token") val token: String,
    @Json(name = "expires_at") val expiresAt: String,
)
