package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ApiErrorDto(
    @Json(name = "error_code") val errorCode: String?,
    @Json(name = "message") val message: String,
    @Json(name = "success") val success: Boolean = false,
)
