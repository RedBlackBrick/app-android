package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LoginResponseDto(
    @Json(name = "user") val user: UserDto,
    @Json(name = "tokens") val tokens: TokenResponseDto,
)
