package com.tradingplatform.app.domain.model

enum class DeviceStatus {
    ONLINE, OFFLINE;

    companion object {
        fun fromApiString(value: String): DeviceStatus = when (value.lowercase()) {
            "online" -> ONLINE
            else -> OFFLINE
        }
    }
}
