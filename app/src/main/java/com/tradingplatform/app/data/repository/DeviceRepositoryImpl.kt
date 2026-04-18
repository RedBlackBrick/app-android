package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.DeviceApi
import com.tradingplatform.app.data.api.DeviceCommandRequestDto
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

        // Purge Room APRÈS sync réussie — transaction atomique
        val now = System.currentTimeMillis()
        deviceDao.upsertAllAndPurge(
            devices.map { it.toEntity(syncedAt = now) },
            cutoffMillis = now - DEVICE_TTL_MS,
        )

        devices
    }

    override suspend fun getDeviceStatus(deviceId: String): Result<Device> = runCatching {
        // Tenter d'abord le cache Room (TTL 1 min — CLAUDE.md §2 stratégie cache)
        val cached = deviceDao.getById(deviceId)
        if (cached != null) return@runCatching cached.toDomain()

        // Cache vide (getDevices() pas encore appelé, ou TTL expiré) :
        // Pas d'endpoint GET /v1/edge/devices/{id} — fallback sur getDevices() complet
        // puis filtre sur le deviceId demandé. Le résultat est upserted dans Room
        // pour les prochains appels.
        // NOTE : si l'API expose un jour GET /v1/edge/devices/{id}, ajouter
        // deviceApi.getDeviceById(deviceId) dans DeviceApi et supprimer ce fallback.
        val response = deviceApi.getDevices()
        if (!response.isSuccessful) {
            error("Get devices (fallback for status) failed: HTTP ${response.code()}")
        }
        val devices = response.body()?.devices?.map { it.toDomain() } ?: emptyList()

        val now = System.currentTimeMillis()
        deviceDao.upsertAllAndPurge(
            devices.map { it.toEntity(syncedAt = now) },
            cutoffMillis = now - DEVICE_TTL_MS,
        )

        devices.firstOrNull { it.id == deviceId }
            ?: error("Device $deviceId not found")
    }

    override suspend fun unpairDevice(deviceId: String): Result<Unit> = runCatching {
        val response = deviceApi.unpairDevice(deviceId)
        if (!response.isSuccessful) {
            error("Unpair device failed: HTTP ${response.code()}")
        }
        deviceDao.deleteByDeviceId(deviceId)
    }

    override suspend fun sendCommand(deviceId: String, commandType: String, params: Map<String, Any>?): Result<Unit> = runCatching {
        val response = deviceApi.sendCommand(
            body = DeviceCommandRequestDto(deviceId = deviceId, commandType = commandType, params = params),
        )
        if (!response.isSuccessful) {
            error("Send command failed: HTTP ${response.code()}")
        }
    }
}
