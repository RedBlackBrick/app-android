package com.tradingplatform.app.data.model

/**
 * Broker gateway state extracted from the flat fields of [DeviceDto].
 * The backend exposes broker_gateway_enabled / broker_gateway_status / broker_gateway_broker_id
 * as flat fields on DeviceResponse — this class groups them for the domain mapping step.
 */
data class BrokerGatewayStatusDto(
    val enabled: Boolean,
    val status: String,
    val brokerId: Int?,
)
