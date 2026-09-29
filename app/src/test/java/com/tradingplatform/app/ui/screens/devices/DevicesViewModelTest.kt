package com.tradingplatform.app.ui.screens.devices

import app.cash.turbine.test
import com.tradingplatform.app.domain.model.BrokerConnection
import com.tradingplatform.app.domain.model.Cached
import com.tradingplatform.app.domain.model.Device
import com.tradingplatform.app.domain.model.DeviceStatus
import com.tradingplatform.app.domain.usecase.device.GetBrokerConnectionsUseCase
import com.tradingplatform.app.domain.usecase.device.GetDevicesUseCase
import com.tradingplatform.app.domain.usecase.device.GetDeviceStatusUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlin.test.assertIs
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

    @Test
    fun `refresh keeps the displayed list on screen while reloading`() = runTest {
        coEvery { getDevicesUseCase() } returns Result.success(listOf(fakeDevice))
        viewModel = DevicesViewModel(getDevicesUseCase)
        assertFalse(viewModel.isRefreshing.value)

        val gate = CompletableDeferred<Result<List<Device>>>()
        coEvery { getDevicesUseCase() } coAnswers { gate.await() }

        viewModel.refresh()

        // Pas de retour au squelette : la liste précédente reste affichée, le spinner s'allume.
        assertTrue(viewModel.isRefreshing.value)
        val during = viewModel.uiState.value
        assertIs<DevicesUiState.Success>(during)
        assertEquals(1, during.devices.size)

        gate.complete(Result.success(listOf(fakeDevice, fakeDeviceOffline)))
        advanceUntilIdle()

        assertFalse(viewModel.isRefreshing.value)
        val after = viewModel.uiState.value
        assertIs<DevicesUiState.Success>(after)
        assertEquals(2, after.devices.size)
    }

    @Test
    fun `initial load shows Loading and does not flag isRefreshing`() = runTest {
        val gate = CompletableDeferred<Result<List<Device>>>()
        coEvery { getDevicesUseCase() } coAnswers { gate.await() }

        viewModel = DevicesViewModel(getDevicesUseCase)

        assertIs<DevicesUiState.Loading>(viewModel.uiState.value)
        assertFalse(viewModel.isRefreshing.value)

        gate.complete(Result.success(emptyList()))
        advanceUntilIdle()

        assertIs<DevicesUiState.Success>(viewModel.uiState.value)
    }
}

// ── DeviceDetailViewModelTest ─────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getDeviceStatusUseCase = mockk<GetDeviceStatusUseCase>()
    private val getBrokerConnectionsUseCase = mockk<GetBrokerConnectionsUseCase>()
    private lateinit var viewModel: DeviceDetailViewModel

    private val fakeDevice = Device(
        id = "device-1",
        name = "Radxa Edge V1",
        status = DeviceStatus.ONLINE,
        wgIp = "10.42.0.5",
        lastHeartbeat = Instant.parse("2026-03-01T10:00:00Z"),
    )

    /** Horodatage réel de la ligne Room — distinct de l'instant d'affichage. */
    private val fakeSyncedAt = 1_700_000_000_000L

    @Before
    fun setUp() {
        viewModel = DeviceDetailViewModel(
            getDeviceStatusUseCase = getDeviceStatusUseCase,
            getBrokerConnectionsUseCase = getBrokerConnectionsUseCase,
        )
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
        coEvery { getDeviceStatusUseCase("device-1", any()) } returns Result.success(Cached(fakeDevice, fakeSyncedAt))

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
        coEvery { getDeviceStatusUseCase("device-99", any()) } returns
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
        coEvery { getDeviceStatusUseCase("device-1", any()) } returns Result.success(Cached(fakeDevice, fakeSyncedAt))

        viewModel.loadDevice("device-1")
        viewModel.refresh("device-1")

        coVerify(exactly = 2) { getDeviceStatusUseCase("device-1", any()) }
    }

    @Test
    fun `loadDevice may serve the cache while refresh forces a network fetch`() = runTest {
        coEvery { getDeviceStatusUseCase("device-1", any()) } returns Result.success(Cached(fakeDevice, fakeSyncedAt))

        viewModel.loadDevice("device-1")
        coVerify(exactly = 1) { getDeviceStatusUseCase("device-1", false) }
        coVerify(exactly = 0) { getDeviceStatusUseCase("device-1", true) }

        viewModel.refresh("device-1")
        coVerify(exactly = 1) { getDeviceStatusUseCase("device-1", true) }
    }

    @Test
    fun `loadDevice exposes the real syncedAt of the data, not the display time`() = runTest {
        coEvery { getDeviceStatusUseCase("device-1", any()) } returns Result.success(Cached(fakeDevice, fakeSyncedAt))

        viewModel.loadDevice("device-1")

        val state = viewModel.uiState.value
        assertIs<DeviceDetailUiState.Success>(state)
        assertEquals(fakeSyncedAt, state.syncedAt)
    }

    @Test
    fun `loadDevice with different id loads correct device`() = runTest {
        val anotherDevice = fakeDevice.copy(id = "device-2", name = "Radxa Edge V2")
        coEvery { getDeviceStatusUseCase("device-2", any()) } returns Result.success(Cached(anotherDevice, fakeSyncedAt))

        viewModel.loadDevice("device-2")

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<DeviceDetailUiState.Success>(state)
            assertEquals("device-2", (state as DeviceDetailUiState.Success).device.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh keeps the displayed device on screen while reloading`() = runTest {
        coEvery { getDeviceStatusUseCase("device-1", false) } returns Result.success(Cached(fakeDevice, fakeSyncedAt))
        viewModel.loadDevice("device-1")
        assertFalse(viewModel.isRefreshing.value)

        val gate = CompletableDeferred<Result<Cached<Device>>>()
        coEvery { getDeviceStatusUseCase("device-1", true) } coAnswers { gate.await() }

        viewModel.refresh("device-1")

        // Pas d'écran de chargement : le device reste affiché, seul le spinner de refresh s'allume.
        assertTrue(viewModel.isRefreshing.value)
        val during = viewModel.uiState.value
        assertIs<DeviceDetailUiState.Success>(during)
        assertEquals("Radxa Edge V1", during.device.name)

        gate.complete(Result.success(Cached(fakeDevice.copy(name = "Radxa Renamed"), fakeSyncedAt + 1)))
        advanceUntilIdle()

        assertFalse(viewModel.isRefreshing.value)
        val after = viewModel.uiState.value
        assertIs<DeviceDetailUiState.Success>(after)
        assertEquals("Radxa Renamed", after.device.name)
        assertEquals(fakeSyncedAt + 1, after.syncedAt)
    }

    @Test
    fun `loading another device does not keep the previous one on screen`() = runTest {
        coEvery { getDeviceStatusUseCase("device-1", any()) } returns Result.success(Cached(fakeDevice, fakeSyncedAt))
        viewModel.loadDevice("device-1")

        val gate = CompletableDeferred<Result<Cached<Device>>>()
        coEvery { getDeviceStatusUseCase("device-2", any()) } coAnswers { gate.await() }

        viewModel.loadDevice("device-2")

        assertIs<DeviceDetailUiState.Loading>(viewModel.uiState.value)
        assertFalse(viewModel.isRefreshing.value)

        gate.complete(Result.success(Cached(fakeDevice.copy(id = "device-2"), fakeSyncedAt)))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertIs<DeviceDetailUiState.Success>(state)
        assertEquals("device-2", state.device.id)
    }

    @Test
    fun `initial broker state is Idle`() = runTest {
        assertIs<BrokerUiState.Idle>(viewModel.brokerState.value)
    }

    @Test
    fun `loadBrokerConnections emits Success with the connections`() = runTest {
        val connections = listOf(
            BrokerConnection(
                deviceId = "device-1",
                portfolioId = "portfolio-1",
                brokerCode = "interactive_brokers",
                connectionStatus = "connected",
            ),
        )
        coEvery { getBrokerConnectionsUseCase("device-1") } returns Result.success(connections)

        viewModel.loadBrokerConnections("device-1")

        val state = viewModel.brokerState.value
        assertIs<BrokerUiState.Success>(state)
        assertEquals(1, state.connections.size)
        assertEquals("interactive_brokers", state.connections[0].brokerCode)
        assertEquals("connected", state.connections[0].connectionStatus)
    }

    @Test
    fun `loadBrokerConnections emits Error on failure`() = runTest {
        coEvery { getBrokerConnectionsUseCase("device-1") } returns
            Result.failure(RuntimeException("Broker service down"))

        viewModel.loadBrokerConnections("device-1")

        val state = viewModel.brokerState.value
        assertIs<BrokerUiState.Error>(state)
        assertEquals("Broker service down", state.message)
    }

    @Test
    fun `loadBrokerConnections keeps the connections on screen while reloading`() = runTest {
        val connection = BrokerConnection(
            deviceId = "device-1",
            portfolioId = null,
            brokerCode = "ibkr",
            connectionStatus = "pending",
        )
        coEvery { getBrokerConnectionsUseCase("device-1") } returns Result.success(listOf(connection))
        viewModel.loadBrokerConnections("device-1")

        val gate = CompletableDeferred<Result<List<BrokerConnection>>>()
        coEvery { getBrokerConnectionsUseCase("device-1") } coAnswers { gate.await() }

        viewModel.loadBrokerConnections("device-1")

        assertIs<BrokerUiState.Success>(viewModel.brokerState.value)

        gate.complete(Result.success(emptyList()))
        advanceUntilIdle()

        val state = viewModel.brokerState.value
        assertIs<BrokerUiState.Success>(state)
        assertTrue(state.connections.isEmpty())
    }
}
