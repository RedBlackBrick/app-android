package com.tradingplatform.app.domain.model

data class BrokerConnection(
    val deviceId: String,
    val portfolioId: String?,
    val brokerCode: String,
    val connectionStatus: String?,
    val executionMode: String?,
)

data class BrokerTestResult(
    val healthy: Boolean,
    val message: String?,
)
