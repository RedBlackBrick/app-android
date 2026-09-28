package com.tradingplatform.app.ui.navigation

import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.data.session.TokenHolder
import com.tradingplatform.app.domain.usecase.auth.AuthContext
import com.tradingplatform.app.domain.usecase.auth.GetAuthContextUseCase
import com.tradingplatform.app.domain.usecase.auth.LogoutUseCase
import com.tradingplatform.app.domain.usecase.auth.RecoverFromKeystoreCorruptionUseCase
import com.tradingplatform.app.security.BiometricLockManager
import com.tradingplatform.app.security.BiometricManager
import com.tradingplatform.app.util.MainDispatcherRule
import com.tradingplatform.app.vpn.SystemVpnMonitor
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests du chemin de récupération après corruption Keystore (audit #17) et de l'escape hatch
 * biométrique (PR 1.7) dans [AppNavViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppNavViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getAuthContextUseCase = mockk<GetAuthContextUseCase>()
    private val corruptionFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val forcedLogoutFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val sessionManager = mockk<SessionManager>(relaxed = true) {
        every { forcedLogoutEvents } returns forcedLogoutFlow
        // Comme le vrai SessionManager : notifyForcedLogout émet sur forcedLogoutEvents.
        every { notifyForcedLogout() } answers { forcedLogoutFlow.tryEmit(Unit); Unit }
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
    private val logoutUseCase = mockk<LogoutUseCase>()
    private val tokenHolder = spyk(TokenHolder().apply { setToken("jwt-access-token") })

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
            logoutUseCase = logoutUseCase,
            tokenHolder = tokenHolder,
        )
        coEvery { logoutUseCase() } returns Result.success(Unit)
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

    // ── Escape hatch biométrique (PR 1.7) ────────────────────────────────────

    @Test
    fun `escape hatch tears the session down and navigates before unlocking`() = runTest {
        viewModel.onBiometricEscapeHatch()
        advanceUntilIdle()

        // Teardown fait, logout forcé émis → navigation Login demandée…
        assertNull("TokenHolder must be cleared by the escape hatch", tokenHolder.accessToken)
        assertEquals(false, viewModel.isLoggedIn.value)
        assertEquals(1, viewModel.forcedLogoutCount.value)
        assertTrue(viewModel.awaitingLoggedOutScreen.value)
        // …mais l'overlay reste verrouillé tant que Login n'est pas affiché.
        verify(exactly = 0) { biometricLockManager.unlock() }

        viewModel.onLoggedOutScreenShown()

        assertFalse(viewModel.awaitingLoggedOutScreen.value)
        coVerifyOrder {
            logoutUseCase()
            tokenHolder.clear()
            sessionManager.notifyForcedLogout()
            biometricLockManager.unlock()
        }
        verify(exactly = 1) { biometricLockManager.unlock() }
    }

    @Test
    fun `escape hatch still clears the token and logs out when the logout use case throws`() = runTest {
        coEvery { logoutUseCase() } throws IllegalStateException("boom")

        viewModel.onBiometricEscapeHatch()
        advanceUntilIdle()

        assertNull(tokenHolder.accessToken)
        assertEquals(false, viewModel.isLoggedIn.value)
        assertTrue(viewModel.awaitingLoggedOutScreen.value)
        verify(exactly = 1) { sessionManager.notifyForcedLogout() }
        verify(exactly = 0) { biometricLockManager.unlock() }
    }

    @Test
    fun `escape hatch does not unlock while the logout is still in flight`() = runTest {
        val gate = CompletableDeferred<Result<Unit>>()
        coEvery { logoutUseCase() } coAnswers { gate.await() }

        viewModel.onBiometricEscapeHatch()

        verify(exactly = 0) { sessionManager.notifyForcedLogout() }
        assertFalse(viewModel.awaitingLoggedOutScreen.value)
        // Un "écran Login affiché" prématuré ne déverrouille pas.
        viewModel.onLoggedOutScreenShown()
        verify(exactly = 0) { biometricLockManager.unlock() }

        gate.complete(Result.success(Unit))
        advanceUntilIdle()

        assertTrue(viewModel.awaitingLoggedOutScreen.value)
        verify(exactly = 1) { sessionManager.notifyForcedLogout() }
        verify(exactly = 0) { biometricLockManager.unlock() }
    }

    @Test
    fun `double escape hatch runs the logout once`() = runTest {
        val gate = CompletableDeferred<Result<Unit>>()
        coEvery { logoutUseCase() } coAnswers { gate.await() }

        viewModel.onBiometricEscapeHatch()
        viewModel.onBiometricEscapeHatch()
        gate.complete(Result.success(Unit))
        advanceUntilIdle()

        coVerify(exactly = 1) { logoutUseCase() }
        verify(exactly = 1) { sessionManager.notifyForcedLogout() }
    }

    @Test
    fun `biometric success during the escape hatch does not unlock`() = runTest {
        viewModel.onBiometricEscapeHatch()
        advanceUntilIdle()

        viewModel.onBiometricUnlocked()

        verify(exactly = 0) { biometricLockManager.unlock() }
    }

    @Test
    fun `logged out screen shown outside of an escape hatch is a no-op`() = runTest {
        viewModel.onLoggedOutScreenShown()

        verify(exactly = 0) { biometricLockManager.unlock() }
    }

    @Test
    fun `biometric success unlocks when no escape hatch is in progress`() = runTest {
        viewModel.onBiometricUnlocked()

        verify(exactly = 1) { biometricLockManager.unlock() }
    }

    @Test
    fun `every forced logout bumps the navigation key even when already logged out`() = runTest {
        // isLoggedIn reste false après un login fait pendant la session : sans compteur,
        // le second logout ne produirait aucune transition et la navigation ne serait pas relancée.
        forcedLogoutFlow.tryEmit(Unit)
        assertEquals(false, viewModel.isLoggedIn.value)
        assertEquals(1, viewModel.forcedLogoutCount.value)

        forcedLogoutFlow.tryEmit(Unit)
        assertEquals(2, viewModel.forcedLogoutCount.value)
    }
}
