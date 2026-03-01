package com.tradingplatform.app.ui.screens.devices

import app.cash.turbine.test
import com.tradingplatform.app.domain.model.Device
import com.tradingplatform.app.domain.model.DeviceStatus
import com.tradingplatform.app.domain.usecase.device.GetDevicesUseCase
import com.tradingplatform.app.domain.usecase.device.GetDeviceStatusUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertIs
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getDevicesUseCase = mockk<GetDevicesUseCase>()
    private lateinit var viewModel: DevicesViewModel

    private val fakeDevice = Device(
        id = "device-1",
        name = "Radxa Edge V1",
        status = DeviceStatus.ONLINE,
        wgIp = "10.42.0.5",
        lastHeartbeat = Instant.parse("2026-03-01T10:00:00Z"),
    )

    private val fakeDeviceOffline = Device(
        id = "device-2",
        name = "Radxa Edge V2",
        status = DeviceStatus.OFFLINE,
        wgIp = "10.42.0.6",
        lastHeartbeat = Instant.parse("2026-03-01T09:00:00Z"),
    )

    // ── DevicesViewModel ──────────────────────────────────────────────────────

    @Test
    fun `init triggers loadDevices and emits Loading then Success`() = runTest {
        coEvery { getDevicesUseCase() } returns Result.success(listOf(fakeDevice))

        viewModel = DevicesViewModel(getDevicesUseCase)

        viewModel.uiState.test {
            // UnconfinedTestDispatcher exécute la coroutine immédiatement —
            // seul l'état final Success est observable ici
            val finalState = awaitItem()
            assertIs<DevicesUiState.Success>(finalState)
            assertEquals(1, (finalState as DevicesUiState.Success).devices.size)
            assertEquals("Radxa Edge V1", finalState.devices[0].name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits Success with correct devices on successful load`() = runTest {
        val devices = listOf(fakeDevice, fakeDeviceOffline)
        coEvery { getDevicesUseCase() } returns Result.success(devices)

        viewModel = DevicesViewModel(getDevicesUseCase)

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<DevicesUiState.Success>(state)
            val success = state as DevicesUiState.Success
            assertEquals(2, success.devices.size)
            assertEquals("device-1", success.devices[0].id)
            assertEquals(DeviceStatus.ONLINE, success.devices[0].status)
            assertEquals(DeviceStatus.OFFLINE, success.devices[1].status)
            assertTrue(success.syncedAt > 0L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits Success with empty list when no devices`() = runTest {
        coEvery { getDevicesUseCase() } returns Result.success(emptyList())

        viewModel = DevicesViewModel(getDevicesUseCase)

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<DevicesUiState.Success>(state)
            assertTrue((state as DevicesUiState.Success).devices.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits Error on repository failure`() = runTest {
        coEvery { getDevicesUseCase() } returns Result.failure(RuntimeException("Network error"))

        viewModel = DevicesViewModel(getDevicesUseCase)

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<DevicesUiState.Error>(state)
            assertEquals("Network error", (state as DevicesUiState.Error).message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits Error with fallback message when exception has no message`() = runTest {
        coEvery { getDevicesUseCase() } returns Result.failure(RuntimeException())

        viewModel = DevicesViewModel(getDevicesUseCase)

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<DevicesUiState.Error>(state)
            assertTrue((state as DevicesUiState.Error).message.isNotBlank())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh reloads devices from repository`() = runTest {
        coEvery { getDevicesUseCase() } returns Result.success(listOf(fakeDevice))

        viewModel = DevicesViewModel(getDevicesUseCase)

        // Attendre le premier chargement
        viewModel.uiState.test {
            awaitItem() // état après init
            cancelAndIgnoreRemainingEvents()
        }

        // Déclencher un refresh
        viewModel.refresh()

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<DevicesUiState.Success>(state)
            cancelAndIgnoreRemainingEvents()
        }

        // Vérifier que le use case a été appelé deux fois (init + refresh)
        coVerify(exactly = 2) { getDevicesUseCase() }
    }

    @Test
    fun `refresh after error reloads devices successfully`() = runTest {
        coEvery { getDevicesUseCase() } returnsMany listOf(
            Result.failure(RuntimeException("Network error")),
            Result.success(listOf(fakeDevice)),
        )

        viewModel = DevicesViewModel(getDevicesUseCase)

        // Premier état : erreur
        viewModel.uiState.test {
            val errorState = awaitItem()
            assertIs<DevicesUiState.Error>(errorState)
            cancelAndIgnoreRemainingEvents()
        }

        // Refresh — succès cette fois
        viewModel.refresh()

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<DevicesUiState.Success>(state)
            assertEquals(1, (state as DevicesUiState.Success).devices.size)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

// ── DeviceDetailViewModelTest ─────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getDeviceStatusUseCase = mockk<GetDeviceStatusUseCase>()
    private lateinit var viewModel: DeviceDetailViewModel

    private val fakeDevice = Device(
        id = "device-1",
        name = "Radxa Edge V1",
        status = DeviceStatus.ONLINE,
        wgIp = "10.42.0.5",
        lastHeartbeat = Instant.parse("2026-03-01T10:00:00Z"),
    )

    @Before
    fun setUp() {
        viewModel = DeviceDetailViewModel(getDeviceStatusUseCase)
    }

    @Test
    fun `initial state is Loading`() = runTest {
        viewModel.uiState.test {
            assertIs<DeviceDetailUiState.Loading>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadDevice emits Success with correct device`() = runTest {
        coEvery { getDeviceStatusUseCase("device-1") } returns Result.success(fakeDevice)

        viewModel.loadDevice("device-1")

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<DeviceDetailUiState.Success>(state)
            val success = state as DeviceDetailUiState.Success
            assertEquals("device-1", success.device.id)
            assertEquals("Radxa Edge V1", success.device.name)
            assertEquals(DeviceStatus.ONLINE, success.device.status)
            assertEquals("10.42.0.5", success.device.wgIp)
            assertTrue(success.syncedAt > 0L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadDevice emits Error on repository failure`() = runTest {
        coEvery { getDeviceStatusUseCase("device-99") } returns
            Result.failure(RuntimeException("Device not found"))

        viewModel.loadDevice("device-99")

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<DeviceDetailUiState.Error>(state)
            assertEquals("Device not found", (state as DeviceDetailUiState.Error).message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh calls loadDevice again`() = runTest {
        coEvery { getDeviceStatusUseCase("device-1") } returns Result.success(fakeDevice)

        viewModel.loadDevice("device-1")
        viewModel.refresh("device-1")

        coVerify(exactly = 2) { getDeviceStatusUseCase("device-1") }
    }

    @Test
    fun `loadDevice with different id loads correct device`() = runTest {
        val anotherDevice = fakeDevice.copy(id = "device-2", name = "Radxa Edge V2")
        coEvery { getDeviceStatusUseCase("device-2") } returns Result.success(anotherDevice)

        viewModel.loadDevice("device-2")

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<DeviceDetailUiState.Success>(state)
            assertEquals("device-2", (state as DeviceDetailUiState.Success).device.id)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
