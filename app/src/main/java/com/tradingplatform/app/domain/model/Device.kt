package com.tradingplatform.app.domain.model

import java.time.Instant

data class Device(
    val id: String,
    val name: String,
    val status: DeviceStatus,
    val wgIp: String,
    val lastHeartbeat: Instant,
)
