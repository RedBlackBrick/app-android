package com.tradingplatform.app.domain.model

import com.squareup.moshi.Json

enum class PositionStatus {
    @Json(name = "open") OPEN,
    @Json(name = "closed") CLOSED,
    @Json(name = "all") ALL;

    fun toApiString(): String = name.lowercase()
}
