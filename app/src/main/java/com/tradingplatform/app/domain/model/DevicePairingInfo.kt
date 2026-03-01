package com.tradingplatform.app.domain.model

data class DevicePairingInfo(
    val deviceId: String,
    val wgPubkey: String,
    val localIp: String,
    val port: Int,
)
