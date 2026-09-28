package com.tradingplatform.app.ui.navigation

import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.domain.usecase.auth.AuthContext
import com.tradingplatform.app.domain.usecase.auth.GetAuthContextUseCase
import com.tradingplatform.app.domain.usecase.auth.RecoverFromKeystoreCorruptionUseCase
import com.tradingplatform.app.security.BiometricLockManager
import com.tradingplatform.app.security.BiometricManager
import com.tradingplatform.app.util.MainDispatcherRule
import com.tradingplatform.app.vpn.SystemVpnMonitor
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests du chemin de récupération après corruption Keystore (audit #17) dans [AppNavViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppNavViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getAuthContextUseCase = mockk<GetAuthContextUseCase>()
    private val corruptionFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val sessionManager = mockk<SessionManager>(relaxed = true) {
        every { forcedLogoutEvents } returns MutableSharedFlow<Unit>()
        every { upgradeRequiredEvents } returns MutableSharedFlow<Unit>()
        every { keystoreCorruptionEvents } returns corruptionFlow
        every { deepLinkEvents } returns MutableSharedFlow<String>()
    }
    private val biometricLockManager = mockk<BiometricLockManager>(relaxed = true) {
        every { isLocked } returns MutableStateFlow(false)
    }
    private val wireGuardManager = mockk<WireGuardManager>(relaxed = true) {
        every { state } returns MutableStateFlow<VpnState>(VpnState.Disconnected)
    }
    private val systemVpnMonitor = mockk<SystemVpnMonitor>(relaxed = true) {
        every { active } returns MutableStateFlow(false)
    }
    private val biometricManager = mockk<BiometricManager>(relaxed = true)
    private val recoverUseCase = mockk<RecoverFromKeystoreCorruptionUseCase>()

    private lateinit var viewModel: AppNavViewModel

    @Before
    fun setUp() {
        coEvery { getAuthContextUseCase() } returns
            AuthContext(isLoggedIn = true, isAdmin = true, setupCompleted = true)
        viewModel = AppNavViewModel(
            getAuthContextUseCase = getAuthContextUseCase,
            sessionManager = sessionManager,
            biometricLockManager = biometricLockManager,
            wireGuardManager = wireGuardManager,
            systemVpnMonitor = systemVpnMonitor,
            biometricManager = biometricManager,
            recoverFromKeystoreCorruptionUseCase = recoverUseCase,
        )
    }

    private fun emitCorruption() {
        corruptionFlow.tryEmit(Unit)
    }

    @Test
    fun `corruption event shows dialog and logs out`() = runTest {
        emitCorruption()

        assertTrue(viewModel.showKeystoreCorruption.value)
        assertEquals(false, viewModel.isLoggedIn.value)
        assertEquals(true, viewModel.isSetupCompleted.value)
    }

    @Test
    fun `acknowledge with successful recovery hides dialog and routes to setup`() = runTest {
        coEvery { recoverUseCase() } returns true
        emitCorruption()

        viewModel.onKeystoreCorruptionAcknowledged()

        coVerify(exactly = 1) { recoverUseCase() }
        assertFalse(viewModel.showKeystoreCorruption.value)
        assertFalse(viewModel.keystoreRecoveryFailed.value)
        assertFalse(viewModel.keystoreRecoveryInProgress.value)
        assertEquals(false, viewModel.isSetupCompleted.value)
        assertEquals(false, viewModel.isLoggedIn.value)
        assertFalse(viewModel.isAdmin.value)
    }

    @Test
    fun `acknowledge with failed recovery keeps dialog in unavailable variant`() = runTest {
        coEvery { recoverUseCase() } returns false
        emitCorruption()

        viewModel.onKeystoreCorruptionAcknowledged()

        assertTrue(viewModel.showKeystoreCorruption.value)
        assertTrue(viewModel.keystoreRecoveryFailed.value)
        assertFalse(viewModel.keystoreRecoveryInProgress.value)
        // Setup non réinitialisé tant que le reset n'a pas abouti
        assertEquals(true, viewModel.isSetupCompleted.value)
    }

    @Test
    fun `acknowledge when recovery throws is treated as failure`() = runTest {
        coEvery { recoverUseCase() } throws IllegalStateException("boom")
        emitCorruption()

        viewModel.onKeystoreCorruptionAcknowledged()

        assertTrue(viewModel.showKeystoreCorruption.value)
        assertTrue(viewModel.keystoreRecoveryFailed.value)
    }

    @Test
    fun `retry after failure then success clears failed flag`() = runTest {
        coEvery { recoverUseCase() } returnsMany listOf(false, true)
        emitCorruption()

        viewModel.onKeystoreCorruptionAcknowledged()
        assertTrue(viewModel.keystoreRecoveryFailed.value)

        viewModel.onKeystoreCorruptionAcknowledged()
        assertFalse(viewModel.keystoreRecoveryFailed.value)
        assertFalse(viewModel.showKeystoreCorruption.value)
        assertEquals(false, viewModel.isSetupCompleted.value)
    }

    @Test
    fun `double acknowledge while recovery in flight runs use case once`() = runTest {
        val gate = CompletableDeferred<Boolean>()
        coEvery { recoverUseCase() } coAnswers { gate.await() }
        emitCorruption()

        viewModel.onKeystoreCorruptionAcknowledged()
        assertTrue(viewModel.keystoreRecoveryInProgress.value)
        viewModel.onKeystoreCorruptionAcknowledged()

        gate.complete(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { recoverUseCase() }
        assertFalse(viewModel.keystoreRecoveryInProgress.value)
        assertEquals(false, viewModel.isSetupCompleted.value)
    }

    @Test
    fun `onSetupCompleted re-arms the setup flag`() = runTest {
        coEvery { recoverUseCase() } returns true
        emitCorruption()
        viewModel.onKeystoreCorruptionAcknowledged()
        assertEquals(false, viewModel.isSetupCompleted.value)

        viewModel.onSetupCompleted()

        assertEquals(true, viewModel.isSetupCompleted.value)
    }
}
