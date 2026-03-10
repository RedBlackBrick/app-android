package com.tradingplatform.app.domain.model

enum class AlertType {
    PRICE_ALERT,
    TRADE_EXECUTED,
    DEVICE_OFFLINE,
    DEVICE_ONLINE,
    DEVICE_UNPAIRED,
    SCRAPING_ERROR,
    OTA_COMPLETE,
    SYSTEM_ERROR,
    PORTFOLIO_UPDATE,
    UNKNOWN;

    companion object {
        fun fromString(value: String): AlertType = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: UNKNOWN
    }
}
