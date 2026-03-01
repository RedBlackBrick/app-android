package com.tradingplatform.app.ui.screens.auth

import android.content.Context
import android.content.pm.PackageManager
import app.cash.turbine.test
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.exception.AccountLockedException
import com.tradingplatform.app.domain.exception.InvalidCredentialsException
import com.tradingplatform.app.domain.exception.TotpRequiredException
import com.tradingplatform.app.domain.model.AuthTokens
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.model.User
import com.tradingplatform.app.domain.usecase.auth.GetPortfoliosUseCase
import com.tradingplatform.app.domain.usecase.auth.LoginUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val loginUseCase = mockk<LoginUseCase>()
    private val getPortfoliosUseCase = mockk<GetPortfoliosUseCase>()
    private val dataStore = mockk<EncryptedDataStore>()
    private val context = mockk<Context>(relaxed = true)
    private val packageManager = mockk<PackageManager>(relaxed = true)
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
    private val fakePortfolio = Portfolio(id = 42, name = "Main Portfolio", currency = "EUR")

    @Before
    fun setUp() {
        every { context.packageManager } returns packageManager
        coEvery { dataStore.readBoolean(DataStoreKeys.IS_ADMIN) } returns false
        viewModel = LoginViewModel(loginUseCase, getPortfoliosUseCase, dataStore, context)
    }

    // ── Cas nominal : login sans TOTP → Success ────────────────────────────────

    @Test
    fun `login success without TOTP emits Loading then Success`() = runTest {
        coEvery { loginUseCase(any(), any()) } returns Result.success(Pair(fakeUser, fakeTokens))
        coEvery { getPortfoliosUseCase() } returns Result.success(listOf(fakePortfolio))

        viewModel.uiState.test {
            assertEquals(LoginUiState.Idle, awaitItem()) // état initial

            viewModel.login("user@example.com", "password123")

            assertEquals(LoginUiState.Loading, awaitItem())
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

            assertEquals(LoginUiState.Loading, awaitItem())
            val state = awaitItem()
            assertTrue(state is LoginUiState.TotpRequired)
            assertEquals(sessionToken, (state as LoginUiState.TotpRequired).sessionToken)

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

            assertEquals(LoginUiState.Loading, awaitItem())
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

            assertEquals(LoginUiState.Loading, awaitItem())
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

            assertEquals(LoginUiState.Loading, awaitItem())
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

            assertEquals(LoginUiState.Loading, awaitItem())
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

            assertEquals(LoginUiState.Loading, awaitItem())
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

            assertEquals(LoginUiState.Loading, awaitItem())
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
}
