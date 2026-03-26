package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LoginResponseDto(
    @Json(name = "user") val user: UserDto? = null,
    @Json(name = "tokens") val tokens: TokenResponseDto? = null,
    @Json(name = "requires_2fa") val requires2fa: Boolean = false,
    @Json(name = "temp_token") val tempToken: String? = null,
    @Json(name = "session_token") val sessionToken: String? = null,
)
