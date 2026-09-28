package com.tradingplatform.app.ui.screens.setup

import app.cash.turbine.test
import com.tradingplatform.app.domain.model.SetupQrData
import com.tradingplatform.app.domain.usecase.pairing.ParseSetupQrUseCase
import com.tradingplatform.app.domain.usecase.pairing.ProvisionMobileVpnUseCase
import com.tradingplatform.app.domain.usecase.pairing.UnrecognizedQrException
import com.tradingplatform.app.domain.usecase.setup.MarkSetupCompletedUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.test.assertIs

/**
 * [SetupViewModel] no longer injects `EncryptedDataStore` directly (CLAUDE.md §2 — a
 * `ViewModel` must go through a `UseCase`). This test verifies [MarkSetupCompletedUseCase]
 * is invoked exactly once when the flow reaches [VpnState.Connected], and never on error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val parseSetupQrUseCase = mockk<ParseSetupQrUseCase>()
    private val provisionMobileVpnUseCase = mockk<ProvisionMobileVpnUseCase>()
    private val wireGuardManager = mockk<WireGuardManager>(relaxed = true)
    private val markSetupCompletedUseCase = mockk<MarkSetupCompletedUseCase>(relaxed = true)
    private lateinit var vpnStateFlow: MutableStateFlow<VpnState>
    private lateinit var viewModel: SetupViewModel

    private val fakeSetupData = SetupQrData(
        version = 1,
        provisioningId = "prov-uuid-123",
        claimToken = "a".repeat(43), // never assert on this value in logs
        nonce = "b".repeat(64),
        vpsEndpoint = "vps.example.com:51820",
        vpsPubkey = "c".repeat(44),
        dns = "",
        expiresAt = Instant.now().plusSeconds(300),
    )

    @Before
    fun setUp() {
        vpnStateFlow = MutableStateFlow(VpnState.Disconnected)
        every { wireGuardManager.state } returns vpnStateFlow
        viewModel = SetupViewModel(
            parseSetupQrUseCase = parseSetupQrUseCase,
            provisionMobileVpnUseCase = provisionMobileVpnUseCase,
            wireGuardManager = wireGuardManager,
            markSetupCompletedUseCase = markSetupCompletedUseCase,
        )
    }

    // ── Nominal path: QR ok + provisioning ok + VPN Connected ────────────────────

    @Test
    fun `QR parsed, provisioning succeeds, VPN connects — markSetupCompletedUseCase invoked once`() = runTest {
        coEvery { parseSetupQrUseCase(any()) } returns Result.success(fakeSetupData)
        coEvery { provisionMobileVpnUseCase(fakeSetupData) } returns Result.success(Unit)
        vpnStateFlow.value = VpnState.Connected("10.42.0.5")

        viewModel.uiState.test {
            assertIs<SetupUiState.Scanning>(awaitItem())

            viewModel.onQrScanned("{valid-setup-qr}")

            assertIs<SetupUiState.Connected>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { markSetupCompletedUseCase() }
    }

    // ── Error paths: markSetupCompletedUseCase must never be invoked ─────────────

    @Test
    fun `QR parse failure — markSetupCompletedUseCase never invoked`() = runTest {
        coEvery { parseSetupQrUseCase(any()) } returns
            Result.failure(UnrecognizedQrException("QR non reconnu"))

        viewModel.uiState.test {
            assertIs<SetupUiState.Scanning>(awaitItem())

            viewModel.onQrScanned("not-a-valid-qr")

            val error = awaitItem()
            assertIs<SetupUiState.Error>(error)
            assertTrue((error as SetupUiState.Error).message.isNotBlank())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { markSetupCompletedUseCase() }
    }

    @Test
    fun `provisioning failure — markSetupCompletedUseCase never invoked`() = runTest {
        coEvery { parseSetupQrUseCase(any()) } returns Result.success(fakeSetupData)
        coEvery { provisionMobileVpnUseCase(fakeSetupData) } returns
            Result.failure(RuntimeException("register call failed"))

        viewModel.uiState.test {
            assertIs<SetupUiState.Scanning>(awaitItem())

            viewModel.onQrScanned("{valid-setup-qr}")

            assertIs<SetupUiState.Error>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { markSetupCompletedUseCase() }
    }

    @Test
    fun `VPN reaches Error state — markSetupCompletedUseCase never invoked`() = runTest {
        coEvery { parseSetupQrUseCase(any()) } returns Result.success(fakeSetupData)
        coEvery { provisionMobileVpnUseCase(fakeSetupData) } returns Result.success(Unit)
        vpnStateFlow.value = VpnState.Error("tunnel handshake failed")

        viewModel.uiState.test {
            assertIs<SetupUiState.Scanning>(awaitItem())

            viewModel.onQrScanned("{valid-setup-qr}")

            val error = awaitItem()
            assertIs<SetupUiState.Error>(error)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { markSetupCompletedUseCase() }
    }
}
