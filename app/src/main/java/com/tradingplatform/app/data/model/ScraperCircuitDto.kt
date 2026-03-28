package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ScraperCircuitDto(
    @Json(name = "state") val state: String,
    @Json(name = "consecutive_failures") val consecutiveFailures: Int,
    @Json(name = "total_trips") val totalTrips: Int,
)
