package com.tradingplatform.app.domain.model

data class SetupQrData(
    val wgPrivateKey: String,
    val wgPublicKeyServer: String,
    val endpoint: String,
    val tunnelIp: String,
    val dns: String,
)
