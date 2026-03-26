package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BrokerSummaryDto(
    @Json(name = "broker_code") val brokerCode: String? = null,
    @Json(name = "connection_status") val connectionStatus: String? = null,
    @Json(name = "execution_mode") val executionMode: String? = null,
    @Json(name = "device_id") val deviceId: String? = null,
)
