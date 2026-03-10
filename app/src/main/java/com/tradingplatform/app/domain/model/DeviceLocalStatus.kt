package com.tradingplatform.app.domain.model

data class DeviceLocalStatus(
    val deviceId: String,
    val wgStatus: String,
    val wifiSsid: String?,
    val uptime: String,
    val lastError: String?,
)
