package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.tradingplatform.app.domain.model.DeviceStatus

@JsonClass(generateAdapter = true)
data class DeviceDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "status") val status: DeviceStatus,
    @Json(name = "wg_ip") val wgIp: String,
    @Json(name = "last_heartbeat") val lastHeartbeat: String?,
    @Json(name = "cpu_pct") val cpuPct: Float? = null,
    @Json(name = "memory_pct") val memoryPct: Float? = null,
    @Json(name = "temperature") val temperature: Float? = null,
    @Json(name = "disk_pct") val diskPct: Float? = null,
    @Json(name = "uptime_seconds") val uptimeSeconds: Long? = null,
    @Json(name = "firmware_version") val firmwareVersion: String? = null,
    @Json(name = "hostname") val hostname: String? = null,
)
