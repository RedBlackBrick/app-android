package com.tradingplatform.app.domain.model

import java.time.Instant

data class Device(
    val id: String,
    val name: String?,
    val status: DeviceStatus,
    val wgIp: String?,
    val lastHeartbeat: Instant?,
    val cpuPct: Float? = null,
    val memoryPct: Float? = null,
    val temperature: Float? = null,
    val diskPct: Float? = null,
    val uptimeSeconds: Long? = null,
    val firmwareVersion: String? = null,
    val hostname: String? = null,
    val brokerGateway: BrokerGatewayStatus? = null,
    val lastTicksSent: Long? = null,
    val lastScraperErrors: Int? = null,
    val scrapersCircuit: Map<String, ScraperCircuitState>? = null,
    val availableMemoryMb: Int? = null,
)

data class ScraperCircuitState(
    val state: String,
    val consecutiveFailures: Int,
    val totalTrips: Int,
)

data class BrokerGatewayStatus(
    val enabled: Boolean,
    val status: String,
    val brokerId: Int?,
)
