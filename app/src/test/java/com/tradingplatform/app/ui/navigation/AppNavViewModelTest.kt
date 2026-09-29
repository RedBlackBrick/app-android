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
import com.tradingplatform.app.vpn.computeEffectiveVpnState
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import io.mockk.clearMocks
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
 * Tests du chemin de récupération après corruption Keystore (audit #17), de l'escape hatch
 * biométrique (PR 1.7) et de l'état de session réactif / startDestination figé (PR 6.1)
 * dans [AppNavViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppNavViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getAuthContextUseCase = mockk<GetAuthContextUseCase>()
    private val corruptionFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val forcedLogoutFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val sessionStartedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val sessionManager = mockk<SessionManager>(relaxed = true) {
        every { forcedLogoutEvents } returns forcedLogoutFlow
        every { sessionStartedEvents } returns sessionStartedFlow
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
        coEvery { logoutUseCase() } returns Result.success(Unit)
        viewModel = createViewModel(
            AuthContext(isLoggedIn = true, isAdmin = true, setupCompleted = true)
        )
    }

    /** Construit le ViewModel avec le contexte d'auth lu « au démarrage ». */
    private fun createViewModel(startup: AuthContext): AppNavViewModel {
        coEvery { getAuthContextUseCase() } returns startup
        return AppNavViewModel(
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
    }

    private fun loggedOutStart() =
        createViewModel(AuthContext(isLoggedIn = false, isAdmin = false, setupCompleted = true))

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
        // Deux logouts forcés consécutifs : isLoggedIn reste false → false (aucune transition
        // de StateFlow) ; seul le compteur relance l'effet de navigation "→ Login".
        forcedLogoutFlow.tryEmit(Unit)
        assertEquals(false, viewModel.isLoggedIn.value)
        assertEquals(1, viewModel.forcedLogoutCount.value)

        forcedLogoutFlow.tryEmit(Unit)
        assertEquals(false, viewModel.isLoggedIn.value)
        assertEquals(2, viewModel.forcedLogoutCount.value)
    }

    // ── isLoggedIn réactif aux événements de session (PR 6.1) ───────────────

    @Test
    fun `session started after a logged-out start sets isLoggedIn and admin refresh reads stored flag`() = runTest {
        val vm = loggedOutStart()
        assertEquals(false, vm.isLoggedIn.value)
        assertFalse(vm.isAdmin.value)

        sessionStartedFlow.tryEmit(Unit)

        assertEquals(true, vm.isLoggedIn.value)

        // Le callback de succès Login/Totp rafraîchit isAdmin une fois IS_ADMIN écrit.
        coEvery { getAuthContextUseCase() } returns
            AuthContext(isLoggedIn = true, isAdmin = true, setupCompleted = true)
        vm.refreshIsAdmin()

        assertTrue(vm.isAdmin.value)
        assertEquals(true, vm.isLoggedIn.value)
    }

    @Test
    fun `session started does not re-read the auth context`() = runTest {
        // AuthRepositoryImpl émet sessionStarted AVANT d'écrire IS_ADMIN : une relecture ici
        // renverrait la valeur précédente. isAdmin n'est rafraîchi que par refreshIsAdmin().
        val vm = loggedOutStart()
        // Oublier les lectures de démarrage (setUp + loggedOutStart), garder les réponses.
        clearMocks(getAuthContextUseCase, answers = false)

        sessionStartedFlow.tryEmit(Unit)

        coVerify(exactly = 0) { getAuthContextUseCase() }
        assertEquals(true, vm.isLoggedIn.value)
        assertFalse(vm.isAdmin.value)
    }

    @Test
    fun `logout then login again toggles isLoggedIn within the same process`() = runTest {
        val vm = loggedOutStart()

        sessionStartedFlow.tryEmit(Unit)
        assertEquals(true, vm.isLoggedIn.value)

        forcedLogoutFlow.tryEmit(Unit)
        assertEquals(false, vm.isLoggedIn.value)
        assertEquals(1, vm.forcedLogoutCount.value)

        sessionStartedFlow.tryEmit(Unit)
        assertEquals(true, vm.isLoggedIn.value)

        forcedLogoutFlow.tryEmit(Unit)
        assertEquals(false, vm.isLoggedIn.value)
        assertEquals(2, vm.forcedLogoutCount.value)
    }

    @Test
    fun `admin refresh after a second login reflects the new account`() = runTest {
        // Admin au démarrage, logout, puis login d'un compte standard.
        forcedLogoutFlow.tryEmit(Unit)
        sessionStartedFlow.tryEmit(Unit)
        coEvery { getAuthContextUseCase() } returns
            AuthContext(isLoggedIn = true, isAdmin = false, setupCompleted = true)

        viewModel.refreshIsAdmin()

        assertFalse(viewModel.isAdmin.value)
        assertEquals(true, viewModel.isLoggedIn.value)
    }

    @Test
    fun `start destination is computed once from the startup context`() = runTest {
        assertEquals(Screen.Dashboard.route, viewModel.startDestination.value)

        forcedLogoutFlow.tryEmit(Unit)
        sessionStartedFlow.tryEmit(Unit)
        forcedLogoutFlow.tryEmit(Unit)
        assertEquals(Screen.Dashboard.route, viewModel.startDestination.value)

        // Reset complet après corruption Keystore : navigation vers Setup par effet, pas par
        // un changement de startDestination (qui remplacerait le graphe du NavHost).
        coEvery { recoverUseCase() } returns true
        emitCorruption()
        viewModel.onKeystoreCorruptionAcknowledged()
        assertEquals(false, viewModel.isSetupCompleted.value)
        assertEquals(Screen.Dashboard.route, viewModel.startDestination.value)
    }

    @Test
    fun `start destination is Login for a logged-out start and stays Login after login`() = runTest {
        val vm = loggedOutStart()
        assertEquals(Screen.Login.route, vm.startDestination.value)

        sessionStartedFlow.tryEmit(Unit)

        assertEquals(Screen.Login.route, vm.startDestination.value)
    }

    @Test
    fun `start destination is Setup on first launch`() = runTest {
        val vm = createViewModel(AuthContext(isLoggedIn = false, isAdmin = false, setupCompleted = false))

        assertEquals(Screen.Setup.route, vm.startDestination.value)
        assertEquals(false, vm.isSetupCompleted.value)
    }

    // ── État VPN perçu (système + intégré) ─────────────────────────────────────

    @Test
    fun `effectiveVpnState starts Connected when a system VPN is already up - no banner flash at cold start`() {
        every { systemVpnMonitor.active } returns MutableStateFlow(true)

        val vm = createViewModel(AuthContext(isLoggedIn = true, isAdmin = false, setupCompleted = true))

        // Lu SANS collecteur : c'est la valeur du 1er rendu, qui valait Disconnected avant le correctif.
        assertTrue(vm.effectiveVpnState.value is VpnState.Connected)
    }

    @Test
    fun `effectiveVpnState initial value asks Android when the monitor callback has not reported yet`() {
        every { systemVpnMonitor.active } returns MutableStateFlow(false)
        every { systemVpnMonitor.isActiveNow() } returns true

        val vm = createViewModel(AuthContext(isLoggedIn = true, isAdmin = false, setupCompleted = true))

        assertTrue(vm.effectiveVpnState.value is VpnState.Connected)
    }

    @Test
    fun `effectiveVpnState stays Disconnected without any VPN`() {
        every { systemVpnMonitor.active } returns MutableStateFlow(false)
        every { systemVpnMonitor.isActiveNow() } returns false

        val vm = createViewModel(AuthContext(isLoggedIn = true, isAdmin = false, setupCompleted = true))

        assertEquals(VpnState.Disconnected, vm.effectiveVpnState.value)
    }

    @Test
    fun `computeEffectiveVpnState rules`() {
        assertTrue(computeEffectiveVpnState(VpnState.Disconnected, systemVpnActive = true) is VpnState.Connected)
        assertEquals(VpnState.Disconnected, computeEffectiveVpnState(VpnState.Disconnected, systemVpnActive = false))
        assertEquals(VpnState.Connecting, computeEffectiveVpnState(VpnState.Connecting, systemVpnActive = false))
        // Un tunnel intégré Connected est conservé tel quel (avec son serverIp).
        val inApp = VpnState.Connected(serverIp = "10.42.0.1")
        assertEquals(inApp, computeEffectiveVpnState(inApp, systemVpnActive = true))
        // ConsentRequired sans VPN système : traité comme déconnecté par l'appelant (bannière).
        assertEquals(VpnState.ConsentRequired, computeEffectiveVpnState(VpnState.ConsentRequired, systemVpnActive = false))
    }
}
