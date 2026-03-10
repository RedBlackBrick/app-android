package com.tradingplatform.app.domain.model

data class DeviceIdentity(
    val deviceId: String,
    val wgPubkey: String,
    val localIp: String,
)
