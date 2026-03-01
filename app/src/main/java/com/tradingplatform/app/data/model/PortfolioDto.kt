package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PortfolioDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "currency") val currency: String,
)
