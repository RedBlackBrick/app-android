package com.tradingplatform.app.domain.model

import com.squareup.moshi.Json

enum class DeviceStatus {
    @Json(name = "online") ONLINE,
    @Json(name = "offline") OFFLINE;

    companion object {
        fun fromApiString(value: String): DeviceStatus = when (value.lowercase()) {
            "online" -> ONLINE
            else -> OFFLINE
        }
    }
}
