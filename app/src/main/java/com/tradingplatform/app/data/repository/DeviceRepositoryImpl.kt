package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.DeviceApi
import com.tradingplatform.app.data.api.DeviceCommandRequestDto
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.data.local.db.dao.DeviceDao
import com.tradingplatform.app.data.model.toDomain
import com.tradingplatform.app.data.model.toEntity
import com.tradingplatform.app.domain.model.Cached
import com.tradingplatform.app.domain.model.Device
import com.tradingplatform.app.domain.repository.DeviceRepository
import com.tradingplatform.app.domain.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class DeviceRepositoryImpl @Inject constructor(
    private val deviceApi: DeviceApi,
    private val deviceDao: DeviceDao,
) : DeviceRepository {

    override suspend fun getDevices(): Result<List<Device>> = runCatchingCancellable {
        fetchAndCacheDevices(System.currentTimeMillis())
    }

    override suspend fun getDeviceStatus(
        deviceId: String,
        forceRefresh: Boolean,
    ): Result<Cached<Device>> = runCatchingCancellable {
        val now = System.currentTimeMillis()
        val cached = deviceDao.getById(deviceId)

        // Cache servi uniquement s'il est frais (TTL devices — CacheTtl / CLAUDE.md §2)
        if (cached != null && !forceRefresh && CacheTtl.isFresh(cached.syncedAt, CacheTtl.DEVICES_MS, now)) {
            return@runCatchingCancellable Cached(cached.toDomain(), cached.syncedAt)
        }

        // Pas d'endpoint GET /v1/edge/devices/{id} — fallback sur getDevices() complet
        // puis filtre sur le deviceId demandé. Le résultat est upserted dans Room.
        // NOTE : si l'API expose un jour GET /v1/edge/devices/{id}, l'utiliser ici.
        val devices = try {
            fetchAndCacheDevices(now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Réseau indisponible : afficher la ligne périmée avec son vrai horodatage
            // (CacheTimestamp la marque « offline ») plutôt qu'une erreur bloquante.
            if (cached != null) return@runCatchingCancellable Cached(cached.toDomain(), cached.syncedAt)
            throw e
        }

        val device = devices.firstOrNull { it.id == deviceId }
            ?: error("Device $deviceId not found")
        Cached(device, now)
    }

    /**
     * `GET /v1/edge/devices` puis upsert + purge Room (APRÈS sync réussie — transaction atomique).
     */
    private suspend fun fetchAndCacheDevices(now: Long): List<Device> {
        val response = deviceApi.getDevices()
        if (!response.isSuccessful) {
            error("Get devices failed: HTTP ${response.code()}")
        }
        val devices = response.body()?.devices?.map { it.toDomain() } ?: emptyList()

        deviceDao.upsertAllAndPurge(
            devices.map { it.toEntity(syncedAt = now) },
            cutoffMillis = now - CacheTtl.DEVICES_MS,
        )
        return devices
    }

    override suspend fun unpairDevice(deviceId: String): Result<Unit> = runCatchingCancellable {
        val response = deviceApi.unpairDevice(deviceId)
        if (!response.isSuccessful) {
            error("Unpair device failed: HTTP ${response.code()}")
        }
        deviceDao.deleteByDeviceId(deviceId)
    }

    override suspend fun sendCommand(deviceId: String, commandType: String, params: Map<String, Any>?): Result<Unit> = runCatchingCancellable {
        val response = deviceApi.sendCommand(
            body = DeviceCommandRequestDto(deviceId = deviceId, commandType = commandType, params = params),
        )
        if (!response.isSuccessful) {
            error("Send command failed: HTTP ${response.code()}")
        }
    }
}
