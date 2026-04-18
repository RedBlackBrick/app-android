package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class DeviceDto(
    @Json(name = "id") val id: String,
    @Json(name = "device_name") val name: String? = null,
    @Json(name = "status") val status: String,
    @Json(name = "ip_address") val wgIp: String? = null,
    @Json(name = "last_heartbeat") val lastHeartbeat: String?,
    @Json(name = "cpu_pct") val cpuPct: Float? = null,
    @Json(name = "memory_pct") val memoryPct: Float? = null,
    @Json(name = "temperature") val temperature: Float? = null,
    @Json(name = "disk_pct") val diskPct: Float? = null,
    @Json(name = "uptime_seconds") val uptimeSeconds: Long? = null,
    @Json(name = "firmware_version") val firmwareVersion: String? = null,
    @Json(name = "hostname") val hostname: String? = null,
    @Json(name = "broker_gateway_enabled") val brokerGatewayEnabled: Boolean? = null,
    @Json(name = "broker_gateway_status") val brokerGatewayStatus: String? = null,
    @Json(name = "broker_gateway_broker_id") val brokerGatewayBrokerId: Int? = null,
    // Server keys are `ticks_sent` / `scraper_errors` (match the SQL column
    // names in identity.edge_devices and the Pydantic DeviceResponse schema).
    // The "last_" prefix was a doc-only fiction that yielded silent nulls on
    // the device dashboard. Kotlin property names kept with "last" for UI
    // continuity — only the JSON key changes.
    @Json(name = "ticks_sent") val lastTicksSent: Long? = null,
    @Json(name = "scraper_errors") val lastScraperErrors: Int? = null,
    @Json(name = "scrapers_circuit") val scrapersCircuit: Map<String, ScraperCircuitDto>? = null,
    @Json(name = "available_memory_mb") val availableMemoryMb: Int? = null,
    // WireGuard link quality — populated from heartbeat (Pydantic DeviceResponse
    // exposes these fields but the DTO was silently dropping them).
    @Json(name = "wg_available") val wgAvailable: Boolean? = null,
    @Json(name = "wg_handshake_age_s") val wgHandshakeAgeSeconds: Int? = null,
    @Json(name = "wg_rx_bytes") val wgRxBytes: Long? = null,
    @Json(name = "wg_tx_bytes") val wgTxBytes: Long? = null,
)
