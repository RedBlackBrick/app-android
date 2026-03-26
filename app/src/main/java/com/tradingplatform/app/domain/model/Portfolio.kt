package com.tradingplatform.app.domain.model

data class Portfolio(
    val id: String,
    val name: String,
    val currency: String,
    val brokerSummary: BrokerSummary? = null,
)

data class BrokerSummary(
    val brokerCode: String?,
    val connectionStatus: String?,
    val executionMode: String?,
    val deviceId: String?,
)
