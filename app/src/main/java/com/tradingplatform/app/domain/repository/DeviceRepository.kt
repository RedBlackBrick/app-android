package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.Device

interface DeviceRepository {
    suspend fun getDevices(): Result<List<Device>>
    suspend fun getDeviceStatus(deviceId: String): Result<Device>
}
