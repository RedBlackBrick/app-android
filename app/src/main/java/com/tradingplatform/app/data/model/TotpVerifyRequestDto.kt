package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TotpVerifyRequestDto(
    @Json(name = "temp_token") val sessionToken: String,
    @Json(name = "totp_code") val totpCode: String,
)
