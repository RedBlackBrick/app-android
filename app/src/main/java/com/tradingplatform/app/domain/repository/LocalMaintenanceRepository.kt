package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.DeviceIdentity
import com.tradingplatform.app.domain.model.DeviceLocalStatus

interface LocalMaintenanceRepository {
    suspend fun sendCommand(
        deviceIp: String,
        devicePort: Int,
        action: String,
        localToken: String,
        radxaWgPubkey: String,
        params: Map<String, String> = emptyMap(),
    ): Result<String>

    suspend fun getStatus(
        deviceIp: String,
        devicePort: Int,
    ): Result<DeviceLocalStatus>

    suspend fun getIdentity(
        deviceIp: String,
        devicePort: Int,
    ): Result<DeviceIdentity>
}
