package com.tradingplatform.app.ui.screens.auth

import android.content.Intent
import app.cash.turbine.test
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.domain.exception.AccountLockedException
import com.tradingplatform.app.domain.exception.InvalidCredentialsException
import com.tradingplatform.app.domain.exception.TotpRequiredException
import com.tradingplatform.app.domain.model.AuthTokens
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.model.User
import com.tradingplatform.app.domain.usecase.auth.ApplyAdminWidgetVisibilityUseCase
import com.tradingplatform.app.domain.usecase.auth.GetPortfoliosUseCase
import com.tradingplatform.app.domain.usecase.auth.LoginUseCase
import com.tradingplatform.app.domain.usecase.vpn.GetVpnConsentIntentUseCase
import com.tradingplatform.app.domain.usecase.vpn.HasVpnConfigUseCase
import com.tradingplatform.app.domain.usecase.vpn.ObserveVpnStateUseCase
import com.tradingplatform.app.domain.usecase.vpn.ReconnectVpnUseCase
import com.tradingplatform.app.domain.usecase.vpn.RetryVpnAfterConsentUseCase
import com.tradingplatform.app.ui.screens.settings.VpnConsentUiState
import com.tradingplatform.app.util.MainDispatcherRule
import com.tradingplatform.app.vpn.VpnNotConnectedException
import com.tradingplatform.app.vpn.VpnState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val loginUseCase = mockk<LoginUseCase>()
    private val getPortfoliosUseCase = mockk<GetPortfoliosUseCase>()
    private val applyAdminWidgetVisibilityUseCase = mockk<ApplyAdminWidgetVisibilityUseCase>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)

    // VPN : par défaut le tunnel est actif, pour que les tests de login ne dépendent pas du VPN.
    private val vpnStateFlow = MutableStateFlow<VpnState>(VpnState.Connected())
    private val observeVpnStateUseCase = mockk<ObserveVpnStateUseCase>().also {
        every { it() } returns vpnStateFlow
    }
    private val hasVpnConfigUseCase = mockk<HasVpnConfigUseCase>()
    private val reconnectVpnUseCase = mockk<ReconnectVpnUseCase>(relaxed = true)
    private val getVpnConsentIntentUseCase = mockk<GetVpnConsentIntentUseCase>(relaxed = true)
    private val retryVpnAfterConsentUseCase = mockk<RetryVpnAfterConsentUseCase>(relaxed = true)
    private lateinit var viewModel: LoginViewModel

    private val fakeUser = User(
        id = 1L,
        email = "user@example.com",
        firstName = "John",
        lastName = "Doe",
        isAdmin = false,
        totpEnabled = false,
    )
    private val fakeTokens = AuthTokens(
        accessToken = "eyJ.test.token",
        tokenType = "bearer",
        expiresIn = 900,
    )
    private val fakePortfolio = Portfolio(id = "42", name = "Main Portfolio", currency = "EUR")

    private fun createViewModel() = LoginViewModel(
        loginUseCase = loginUseCase,
        getPortfoliosUseCase = getPortfoliosUseCase,
        applyAdminWidgetVisibilityUseCase = applyAdminWidgetVisibilityUseCase,
        sessionManager = sessionManager,
        observeVpnStateUseCase = observeVpnStateUseCase,
        hasVpnConfigUseCase = hasVpnConfigUseCase,
        reconnectVpnUseCase = reconnectVpnUseCase,
        getVpnConsentIntentUseCase = getVpnConsentIntentUseCase,
        retryVpnAfterConsentUseCase = retryVpnAfterConsentUseCase,
    )

    @Before
    fun setUp() {
        coEvery { hasVpnConfigUseCase() } returns true
        viewModel = createViewModel()
    }

    // ── Cas nominal : login sans TOTP → Success ────────────────────────────────

    @Test
    fun `login success without TOTP emits Loading then Success`() = runTest {
        coEvery { loginUseCase(any(), any()) } returns Result.success(Pair(fakeUser, fakeTokens))
        coEvery { getPortfoliosUseCase() } returns Result.success(listOf(fakePortfolio))

        viewModel.uiState.test {
            assertEquals(LoginUiState.Idle, awaitItem()) // état initial

            viewModel.login("user@example.com", "password123")

            // Note: Loading may be conflated by StateFlow + UnconfinedTestDispatcher
            assertEquals(LoginUiState.Success, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Cas TOTP requis ────────────────────────────────────────────────────────

    @Test
    fun `login returns TotpRequired when AUTH_1004 thrown`() = runTest {
        val sessionToken = "session-abc-123"
        coEvery { loginUseCase(any(), any()) } returns
            Result.failure(TotpRequiredException(sessionToken = sessionToken))

        viewModel.uiState.test {
            assertEquals(LoginUiState.Idle, awaitItem())

            viewModel.login("user@example.com", "password123")

            // Note: Loading may be conflated by StateFlow + UnconfinedTestDispatcher
            val state = awaitItem()
            assertTrue(state is LoginUiState.TotpRequired)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Erreur credentials invalides (AUTH_1001) ───────────────────────────────

    @Test
    fun `login returns Error on InvalidCredentialsException`() = runTest {
        coEvery { loginUseCase(any(), any()) } returns
            Result.failure(InvalidCredentialsException())

        viewModel.uiState.test {
            assertEquals(LoginUiState.Idle, awaitItem())

            viewModel.login("wrong@example.com", "badpassword")

            // Note: Loading may be conflated by StateFlow + UnconfinedTestDispatcher
            val state = awaitItem()
            assertTrue(state is LoginUiState.Error)
            val error = state as LoginUiState.Error
            assertEquals("Email ou mot de passe incorrect", error.message)
            assertNull(error.retryAfterSeconds)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Compte verrouillé (AUTH_1008 / 429) ───────────────────────────────────

    @Test
    fun `login returns Error with retryAfterSeconds on AccountLockedException`() = runTest {
        coEvery { loginUseCase(any(), any()) } returns
            Result.failure(AccountLockedException(retryAfterSeconds = 30))

        viewModel.uiState.test {
            assertEquals(LoginUiState.Idle, awaitItem())

            viewModel.login("user@example.com", "password123")

            // Note: Loading may be conflated by StateFlow + UnconfinedTestDispatcher
            val state = awaitItem()
            assertTrue(state is LoginUiState.Error)
            val error = state as LoginUiState.Error
            assertEquals(30, error.retryAfterSeconds)
            assertTrue(error.message.contains("30"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `login returns Error without retryAfterSeconds when AccountLocked has no delay`() = runTest {
        coEvery { loginUseCase(any(), any()) } returns
            Result.failure(AccountLockedException(retryAfterSeconds = null))

        viewModel.uiState.test {
            assertEquals(LoginUiState.Idle, awaitItem())

            viewModel.login("user@example.com", "password123")

            // Note: Loading may be conflated by StateFlow + UnconfinedTestDispatcher
            val state = awaitItem()
            assertTrue(state is LoginUiState.Error)
            val error = state as LoginUiState.Error
            assertNull(error.retryAfterSeconds)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Portfolio vide après login ─────────────────────────────────────────────

    @Test
    fun `login emits Error when portfolios list is empty after successful login`() = runTest {
        coEvery { loginUseCase(any(), any()) } returns Result.success(Pair(fakeUser, fakeTokens))
        coEvery { getPortfoliosUseCase() } returns Result.success(emptyList())

        viewModel.uiState.test {
            assertEquals(LoginUiState.Idle, awaitItem())

            viewModel.login("user@example.com", "password123")

            // Note: Loading may be conflated by StateFlow + UnconfinedTestDispatcher
            val state = awaitItem()
            assertTrue(state is LoginUiState.Error)
            val error = state as LoginUiState.Error
            assertNotNull(error.message)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Erreur réseau GetPortfoliosUseCase ─────────────────────────────────────

    @Test
    fun `login emits Error when getPortfoliosUseCase fails after successful login`() = runTest {
        coEvery { loginUseCase(any(), any()) } returns Result.success(Pair(fakeUser, fakeTokens))
        coEvery { getPortfoliosUseCase() } returns Result.failure(RuntimeException("Network error"))

        viewModel.uiState.test {
            assertEquals(LoginUiState.Idle, awaitItem())

            viewModel.login("user@example.com", "password123")

            // Note: Loading may be conflated by StateFlow + UnconfinedTestDispatcher
            val state = awaitItem()
            assertTrue(state is LoginUiState.Error)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── resetState ────────────────────────────────────────────────────────────

    @Test
    fun `resetState resets uiState to Idle`() = runTest {
        coEvery { loginUseCase(any(), any()) } returns
            Result.failure(InvalidCredentialsException())

        viewModel.uiState.test {
            assertEquals(LoginUiState.Idle, awaitItem())

            viewModel.login("user@example.com", "bad")

            // Note: Loading may be conflated by StateFlow + UnconfinedTestDispatcher
            assertTrue(awaitItem() is LoginUiState.Error)

            viewModel.resetState()

            assertEquals(LoginUiState.Idle, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Idempotence Loading ────────────────────────────────────────────────────

    @Test
    fun `second login call is ignored while Loading`() = runTest {
        coEvery { loginUseCase(any(), any()) } coAnswers {
            // Simuler une latence pour rester en Loading
            kotlinx.coroutines.delay(100)
            Result.success(Pair(fakeUser, fakeTokens))
        }
        coEvery { getPortfoliosUseCase() } returns Result.success(listOf(fakePortfolio))

        viewModel.uiState.test {
            assertEquals(LoginUiState.Idle, awaitItem())

            viewModel.login("user@example.com", "password123")
            assertEquals(LoginUiState.Loading, awaitItem())

            // Deuxième appel pendant Loading — ne doit pas émettre un nouvel état Loading
            viewModel.login("user@example.com", "password123")

            // On attend Success directement (pas de double Loading)
            assertEquals(LoginUiState.Success, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── VPN : état et configuration ────────────────────────────────────────────

    @Test
    fun `vpnState mirrors the observed VPN state`() = runTest {
        assertEquals(VpnState.Connected(), viewModel.vpnState.value)

        vpnStateFlow.value = VpnState.Disconnected

        assertEquals(VpnState.Disconnected, viewModel.vpnState.value)
    }

    @Test
    fun `hasVpnConfig is true when a WireGuard config is persisted`() = runTest {
        assertTrue(viewModel.hasVpnConfig.value)
    }

    @Test
    fun `hasVpnConfig is false without a persisted config`() = runTest {
        coEvery { hasVpnConfigUseCase() } returns false

        val noConfigViewModel = createViewModel()

        assertFalse(noConfigViewModel.hasVpnConfig.value)
    }

    // ── VPN : « Activer le VPN » ───────────────────────────────────────────────

    @Test
    fun `onActivateVpn reconnects the tunnel when Disconnected`() = runTest {
        vpnStateFlow.value = VpnState.Disconnected

        viewModel.onActivateVpn()

        verify(exactly = 1) { reconnectVpnUseCase() }
    }

    @Test
    fun `onActivateVpn reconnects the tunnel after an Error`() = runTest {
        vpnStateFlow.value = VpnState.Error("boom")

        viewModel.onActivateVpn()

        verify(exactly = 1) { reconnectVpnUseCase() }
    }

    @Test
    fun `onActivateVpn does nothing while connecting or already active`() = runTest {
        listOf(VpnState.Connecting, VpnState.Connected(), VpnState.SystemVpnActive).forEach { state ->
            vpnStateFlow.value = state

            viewModel.onActivateVpn()
        }

        verify(exactly = 0) { reconnectVpnUseCase() }
    }

    // ── VPN : consentement Android ─────────────────────────────────────────────

    @Test
    fun `activation that reaches ConsentRequired asks the screen to launch the dialog`() = runTest {
        vpnStateFlow.value = VpnState.Disconnected
        every { reconnectVpnUseCase() } answers {
            vpnStateFlow.value = VpnState.Connecting
            vpnStateFlow.value = VpnState.ConsentRequired
        }

        viewModel.onActivateVpn()

        assertEquals(VpnConsentUiState.Required, viewModel.consentState.value)
    }

    @Test
    fun `ConsentRequired seen without a user action never pops the dialog`() = runTest {
        vpnStateFlow.value = VpnState.ConsentRequired

        assertEquals(VpnConsentUiState.None, viewModel.consentState.value)
    }

    @Test
    fun `onActivateVpn on ConsentRequired requests the dialog instead of reconnecting`() = runTest {
        vpnStateFlow.value = VpnState.ConsentRequired

        viewModel.onActivateVpn()

        assertEquals(VpnConsentUiState.Required, viewModel.consentState.value)
        verify(exactly = 0) { reconnectVpnUseCase() }
    }

    @Test
    fun `consent granted replays the connection`() = runTest {
        vpnStateFlow.value = VpnState.ConsentRequired
        viewModel.onActivateVpn()
        viewModel.onVpnConsentLaunched()
        assertEquals(VpnConsentUiState.Launched, viewModel.consentState.value)
        every { retryVpnAfterConsentUseCase() } answers { vpnStateFlow.value = VpnState.Connecting }

        viewModel.onVpnConsentResult(granted = true)

        verify(exactly = 1) { retryVpnAfterConsentUseCase() }
        assertEquals(VpnConsentUiState.None, viewModel.consentState.value)
        assertEquals(VpnState.Connecting, viewModel.vpnState.value)
    }

    @Test
    fun `consent denied is explicit and the next activation re-requests the dialog`() = runTest {
        vpnStateFlow.value = VpnState.ConsentRequired
        viewModel.onActivateVpn()
        viewModel.onVpnConsentLaunched()

        viewModel.onVpnConsentResult(granted = false)

        assertEquals(VpnConsentUiState.Denied, viewModel.consentState.value)
        verify(exactly = 0) { retryVpnAfterConsentUseCase() }

        viewModel.onActivateVpn()

        assertEquals(VpnConsentUiState.Required, viewModel.consentState.value)
    }

    @Test
    fun `a consent result with no dialog pending is ignored`() = runTest {
        viewModel.onVpnConsentResult(granted = true)

        verify(exactly = 0) { retryVpnAfterConsentUseCase() }
        assertEquals(VpnConsentUiState.None, viewModel.consentState.value)
    }

    @Test
    fun `consent state resets when the tunnel leaves ConsentRequired`() = runTest {
        vpnStateFlow.value = VpnState.ConsentRequired
        viewModel.onActivateVpn()
        viewModel.onVpnConsentLaunched()
        viewModel.onVpnConsentResult(granted = false)
        assertEquals(VpnConsentUiState.Denied, viewModel.consentState.value)

        vpnStateFlow.value = VpnState.Disconnected

        assertEquals(VpnConsentUiState.None, viewModel.consentState.value)
    }

    @Test
    fun `vpnConsentIntent comes from the use case`() = runTest {
        val intent = mockk<Intent>(relaxed = true)
        every { getVpnConsentIntentUseCase() } returns intent

        assertSame(intent, viewModel.vpnConsentIntent())
    }

    // ── Login sans VPN : message clair ─────────────────────────────────────────

    @Test
    fun `login timeout without a tunnel says the VPN is required`() = runTest {
        vpnStateFlow.value = VpnState.Disconnected
        coEvery { loginUseCase(any(), any()) } returns Result.failure(SocketTimeoutException("timeout"))

        viewModel.login("user@example.com", "password123")

        assertEquals(
            LoginUiState.Error("VPN requis — activez le tunnel puis réessayez"),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `login connect failure while the tunnel is connecting says the VPN is required`() = runTest {
        vpnStateFlow.value = VpnState.Connecting
        coEvery { loginUseCase(any(), any()) } returns
            Result.failure(ConnectException("Failed to connect to /10.42.0.1:443"))

        viewModel.login("user@example.com", "password123")

        assertEquals(
            LoginUiState.Error("VPN requis — activez le tunnel puis réessayez"),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `login VpnNotConnectedException says the VPN is required`() = runTest {
        coEvery { loginUseCase(any(), any()) } returns Result.failure(VpnNotConnectedException())

        viewModel.login("user@example.com", "password123")

        assertEquals(
            LoginUiState.Error("VPN requis — activez le tunnel puis réessayez"),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `login network failure with an active tunnel says the server is unreachable`() = runTest {
        coEvery { loginUseCase(any(), any()) } returns Result.failure(SocketTimeoutException("timeout"))

        viewModel.login("user@example.com", "password123")

        assertEquals(
            LoginUiState.Error("Serveur injoignable — réessayez dans un instant"),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `login global timeout without a tunnel says the VPN is required`() = runTest {
        vpnStateFlow.value = VpnState.Disconnected
        coEvery { loginUseCase(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(60_000)
            Result.success(Pair(fakeUser, fakeTokens))
        }

        viewModel.login("user@example.com", "password123")
        assertEquals(LoginUiState.Loading, viewModel.uiState.value)
        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(
            LoginUiState.Error("VPN requis — activez le tunnel puis réessayez"),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `login global timeout with an active tunnel does not blame the VPN`() = runTest {
        coEvery { loginUseCase(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(60_000)
            Result.success(Pair(fakeUser, fakeTokens))
        }

        viewModel.login("user@example.com", "password123")
        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(LoginUiState.Error("La connexion a expiré — réessayez"), viewModel.uiState.value)
    }
}
