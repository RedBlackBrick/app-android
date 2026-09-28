package com.tradingplatform.app.ui.screens.setup

import android.content.Intent
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
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    // ── VPN consent (VpnService.prepare) ─────────────────────────────────────────

    /** Drives the flow up to the point where WireGuardManager reports ConsentRequired. */
    private fun reachConsentRequired() {
        coEvery { parseSetupQrUseCase(any()) } returns Result.success(fakeSetupData)
        coEvery { provisionMobileVpnUseCase(fakeSetupData) } returns Result.success(Unit)
        vpnStateFlow.value = VpnState.ConsentRequired
        viewModel.onQrScanned("{valid-setup-qr}")
    }

    @Test
    fun `ConsentRequired from the manager moves the screen to VpnConsentRequired`() = runTest {
        val consentIntent = mockk<Intent>(relaxed = true)
        every { wireGuardManager.prepareIntent() } returns consentIntent

        reachConsentRequired()

        assertEquals(SetupUiState.VpnConsentRequired(launched = false), viewModel.uiState.value)
        assertSame(consentIntent, viewModel.vpnConsentIntent())
        coVerify(exactly = 0) { markSetupCompletedUseCase() }
    }

    @Test
    fun `onVpnConsentLaunched marks the dialog as launched (no second launch)`() = runTest {
        reachConsentRequired()

        viewModel.onVpnConsentLaunched()

        assertEquals(SetupUiState.VpnConsentRequired(launched = true), viewModel.uiState.value)
    }

    @Test
    fun `consent granted — connect retried and setup completes once the tunnel is up`() = runTest {
        every { wireGuardManager.retryAfterConsent() } answers {
            // Real manager publishes Connecting synchronously, then UP once GoBackend is done.
            vpnStateFlow.value = VpnState.Connecting
        }
        reachConsentRequired()
        viewModel.onVpnConsentLaunched()

        viewModel.onVpnConsentResult(granted = true)

        verify(exactly = 1) { wireGuardManager.retryAfterConsent() }
        assertIs<SetupUiState.Connecting>(viewModel.uiState.value)

        vpnStateFlow.value = VpnState.Connected("10.42.0.5")

        assertIs<SetupUiState.Connected>(viewModel.uiState.value)
        coVerify(exactly = 1) { markSetupCompletedUseCase() }
    }

    @Test
    fun `consent granted but still required — back to VpnConsentRequired`() = runTest {
        every { wireGuardManager.retryAfterConsent() } answers {
            vpnStateFlow.value = VpnState.Connecting
            vpnStateFlow.value = VpnState.ConsentRequired
        }
        reachConsentRequired()
        viewModel.onVpnConsentLaunched()

        viewModel.onVpnConsentResult(granted = true)

        assertEquals(SetupUiState.VpnConsentRequired(launched = false), viewModel.uiState.value)
    }

    @Test
    fun `consent denied — explicit error, no connect retry, retry re-asks consent`() = runTest {
        reachConsentRequired()
        viewModel.onVpnConsentLaunched()

        viewModel.onVpnConsentResult(granted = false)

        val denied = viewModel.uiState.value
        assertIs<SetupUiState.VpnConsentDenied>(denied)
        assertEquals(
            "Autorisation VPN refusée — le tunnel est requis pour utiliser l'application",
            (denied as SetupUiState.VpnConsentDenied).message,
        )
        verify(exactly = 0) { wireGuardManager.retryAfterConsent() }
        coVerify(exactly = 0) { markSetupCompletedUseCase() }

        viewModel.retryVpnConsent()

        assertEquals(SetupUiState.VpnConsentRequired(launched = false), viewModel.uiState.value)
    }

    @Test
    fun `onVpnConsentResult is ignored outside VpnConsentRequired`() = runTest {
        viewModel.onVpnConsentResult(granted = true)

        assertIs<SetupUiState.Scanning>(viewModel.uiState.value)
        verify(exactly = 0) { wireGuardManager.retryAfterConsent() }
    }
}
