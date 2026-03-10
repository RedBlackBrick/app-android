package com.tradingplatform.app.domain.model

data class PairingSession(
    val sessionId: String,
    val sessionPin: String,
    val deviceWgIp: String,
    val localToken: String,
)
