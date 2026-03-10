package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.DeviceApi
import com.tradingplatform.app.data.local.db.dao.DeviceDao
import com.tradingplatform.app.data.model.toDomain
import com.tradingplatform.app.data.model.toEntity
import com.tradingplatform.app.domain.model.Device
import com.tradingplatform.app.domain.repository.DeviceRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRepositoryImpl @Inject constructor(
    private val deviceApi: DeviceApi,
    private val deviceDao: DeviceDao,
) : DeviceRepository {

    // TTL devices : 1 min (CLAUDE.md §2 — Stratégie cache Room)
    private val DEVICE_TTL_MS = 60 * 1000L

    override suspend fun getDevices(): Result<List<Device>> = runCatching {
        val response = deviceApi.getDevices()
        if (!response.isSuccessful) {
            error("Get devices failed: HTTP ${response.code()}")
        }
        val devices = response.body()?.devices?.map { it.toDomain() } ?: emptyList()

        // Purge Room APRÈS sync réussie — jamais avant
        val now = System.currentTimeMillis()
        deviceDao.upsertAll(devices.map { it.toEntity(syncedAt = now) })
        deviceDao.deleteOlderThan(now - DEVICE_TTL_MS)

        devices
    }

    override suspend fun getDeviceStatus(deviceId: String): Result<Device> = runCatching {
        // Lire depuis le cache Room — getDevices() est appelé en amont par le ViewModel/UseCase
        deviceDao.getById(deviceId)?.toDomain()
            ?: error("Device $deviceId not found in cache")
    }

    override suspend fun unpairDevice(deviceId: String): Result<Unit> = runCatching {
        val response = deviceApi.unpairDevice(deviceId)
        if (!response.isSuccessful) {
            error("Unpair device failed: HTTP ${response.code()}")
        }
        deviceDao.deleteByDeviceId(deviceId)
    }
}
