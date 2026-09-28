package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.DeviceApi
import com.tradingplatform.app.data.api.DeviceListResponseDto
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.data.local.db.dao.DeviceDao
import com.tradingplatform.app.data.local.db.entity.DeviceEntity
import com.tradingplatform.app.data.model.DeviceDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Audit C-dev-corr-1 (#20) — `getDeviceStatus` servait la ligne Room sans TTL (le refresh de
 * l'écran détail ne touchait jamais le réseau une fois la ligne présente) et estampillait
 * `syncedAt = now()`. Désormais : cache seulement si frais (< CacheTtl.DEVICES_MS) et non
 * forcé, avec le vrai `syncedAt` de la ligne.
 */
class DeviceRepositoryImplTest {

    private val deviceApi = mockk<DeviceApi>()
    private val deviceDao = mockk<DeviceDao>(relaxed = true)
    private val repository = DeviceRepositoryImpl(deviceApi, deviceDao)

    private fun entity(syncedAt: Long, status: String = "offline") = DeviceEntity(
        id = "device-1",
        name = "Radxa cache",
        status = status,
        wgIp = "10.42.0.5",
        lastHeartbeat = null,
        syncedAt = syncedAt,
    )

    private val networkDto = DeviceDto(
        id = "device-1",
        name = "Radxa réseau",
        status = "online",
        lastHeartbeat = null,
    )

    private fun stubNetworkSuccess() {
        coEvery { deviceApi.getDevices() } returns
            Response.success(DeviceListResponseDto(devices = listOf(networkDto)))
    }

    @Test
    fun `fresh cache is served without network, with the row's real syncedAt`() = runTest {
        val syncedAt = System.currentTimeMillis() - 10_000L
        coEvery { deviceDao.getById("device-1") } returns entity(syncedAt)

        val cached = repository.getDeviceStatus("device-1").getOrThrow()

        assertEquals("Radxa cache", cached.value.name)
        assertEquals(syncedAt, cached.syncedAt)
        coVerify(exactly = 0) { deviceApi.getDevices() }
    }

    @Test
    fun `stale cache triggers a network fetch`() = runTest {
        val staleSyncedAt = System.currentTimeMillis() - CacheTtl.DEVICES_MS - 5_000L
        coEvery { deviceDao.getById("device-1") } returns entity(staleSyncedAt)
        stubNetworkSuccess()
        val before = System.currentTimeMillis()

        val cached = repository.getDeviceStatus("device-1").getOrThrow()

        assertEquals("Radxa réseau", cached.value.name)
        assertTrue(cached.syncedAt >= before)
        coVerify(exactly = 1) { deviceApi.getDevices() }
        coVerify(exactly = 1) { deviceDao.upsertAllAndPurge(any(), any()) }
    }

    @Test
    fun `forceRefresh bypasses a fresh cache`() = runTest {
        coEvery { deviceDao.getById("device-1") } returns entity(System.currentTimeMillis())
        stubNetworkSuccess()

        val cached = repository.getDeviceStatus("device-1", forceRefresh = true).getOrThrow()

        assertEquals("Radxa réseau", cached.value.name)
        coVerify(exactly = 1) { deviceApi.getDevices() }
    }

    @Test
    fun `network failure falls back to the stale row with its real syncedAt`() = runTest {
        val staleSyncedAt = System.currentTimeMillis() - 10 * 60_000L
        coEvery { deviceDao.getById("device-1") } returns entity(staleSyncedAt)
        coEvery { deviceApi.getDevices() } throws IOException("timeout")

        val cached = repository.getDeviceStatus("device-1", forceRefresh = true).getOrThrow()

        assertEquals("Radxa cache", cached.value.name)
        assertEquals(staleSyncedAt, cached.syncedAt)
    }

    @Test
    fun `network failure without cache is a failure`() = runTest {
        coEvery { deviceDao.getById("device-1") } returns null
        coEvery { deviceApi.getDevices() } returns Response.error(503, "down".toResponseBody(null))

        val result = repository.getDeviceStatus("device-1")

        assertTrue(result.isFailure)
    }

    @Test
    fun `device absent from the network list is a failure`() = runTest {
        coEvery { deviceDao.getById("device-9") } returns null
        stubNetworkSuccess()

        val result = repository.getDeviceStatus("device-9")

        assertTrue(result.isFailure)
    }

    @Test
    fun `a syncedAt in the future is treated as stale`() = runTest {
        coEvery { deviceDao.getById("device-1") } returns entity(System.currentTimeMillis() + 60 * 60_000L)
        stubNetworkSuccess()

        repository.getDeviceStatus("device-1").getOrThrow()

        coVerify(exactly = 1) { deviceApi.getDevices() }
    }
}
