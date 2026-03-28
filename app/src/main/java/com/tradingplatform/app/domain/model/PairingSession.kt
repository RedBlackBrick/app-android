package com.tradingplatform.app.domain.model

data class PairingSession(
    val sessionId: String,
    val sessionPin: String,
    val deviceWgIp: String,
    val localToken: String,
    val nonce: String,
) {
    override fun toString(): String =
        "PairingSession(sessionId=$sessionId, sessionPin=[REDACTED], deviceWgIp=$deviceWgIp, localToken=[REDACTED], nonce=[REDACTED])"
}
