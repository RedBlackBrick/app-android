package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.Cached
import com.tradingplatform.app.domain.model.Device

interface DeviceRepository {
    suspend fun getDevices(): Result<List<Device>>
    /**
     * État d'un device. Sert le cache Room uniquement s'il est frais (< `CacheTtl.DEVICES_MS`)
     * et que [forceRefresh] est faux ; sinon re-fetch réseau. Si le réseau échoue et qu'une
     * ligne (périmée) existe, elle est renvoyée avec son vrai `syncedAt`.
     */
    suspend fun getDeviceStatus(deviceId: String, forceRefresh: Boolean = false): Result<Cached<Device>>
    suspend fun unpairDevice(deviceId: String): Result<Unit>
    suspend fun sendCommand(deviceId: String, commandType: String, params: Map<String, Any>? = null): Result<Unit>
}
