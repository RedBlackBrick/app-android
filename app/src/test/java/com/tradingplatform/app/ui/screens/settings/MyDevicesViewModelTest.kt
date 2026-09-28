package com.tradingplatform.app.ui.screens.settings

import app.cash.turbine.test
import com.tradingplatform.app.domain.model.VpnPeer
import com.tradingplatform.app.domain.model.VpnPeerType
import com.tradingplatform.app.domain.usecase.device.GetMyDevicesUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class MyDevicesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getMyDevicesUseCase = mockk<GetMyDevicesUseCase>()

    private val fakePeer = VpnPeer(
        id = "peer-1",
        userId = 1,
        label = "Pixel 8",
        peerType = VpnPeerType.ANDROID_APP,
        wgTunnelIp = "10.42.0.7",
        isActive = true,
        pairedAt = Instant.parse("2026-01-01T00:00:00Z"),
        lastHandshake = Instant.parse("2026-09-28T10:00:00Z"),
    )

    private fun createViewModel(): MyDevicesViewModel = MyDevicesViewModel(getMyDevicesUseCase)

    // ── init / loadDevices ───────────────────────────────────────────────────

    @Test
    fun `uiState emits Success with the loaded peers`() = runTest {
        coEvery { getMyDevicesUseCase() } returns Result.success(listOf(fakePeer))

        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<MyDevicesUiState.Success>(state)
            assertEquals(listOf(fakePeer), state.peers)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState carries a syncedAt timestamp on success`() = runTest {
        coEvery { getMyDevicesUseCase() } returns Result.success(listOf(fakePeer))
        val before = System.currentTimeMillis()

        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<MyDevicesUiState.Success>(state)
            assertTrue(
                "Expected syncedAt to be set to roughly now",
                state.syncedAt >= before,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState emits Error when GetMyDevicesUseCase fails`() = runTest {
        coEvery { getMyDevicesUseCase() } returns Result.failure(RuntimeException("VPN down"))

        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<MyDevicesUiState.Error>(state)
            assertEquals("VPN down", state.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState emits Success with an empty list when the user has no paired devices`() = runTest {
        coEvery { getMyDevicesUseCase() } returns Result.success(emptyList())

        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<MyDevicesUiState.Success>(state)
            assertTrue(state.peers.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── refresh ──────────────────────────────────────────────────────────────

    @Test
    fun `refresh reloads devices from GetMyDevicesUseCase`() = runTest {
        coEvery { getMyDevicesUseCase() } returns Result.success(listOf(fakePeer))

        val viewModel = createViewModel()
        viewModel.refresh()

        coVerify(exactly = 2) { getMyDevicesUseCase() }
    }

    @Test
    fun `refresh recovers to Success after a prior failure`() = runTest {
        coEvery { getMyDevicesUseCase() } returns Result.failure(RuntimeException("VPN down"))
        val viewModel = createViewModel()

        coEvery { getMyDevicesUseCase() } returns Result.success(listOf(fakePeer))
        viewModel.refresh()

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<MyDevicesUiState.Success>(state)
            assertEquals(listOf(fakePeer), state.peers)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
