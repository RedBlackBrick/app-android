package com.tradingplatform.app.ui.screens.totp

import app.cash.turbine.test
import com.tradingplatform.app.domain.exception.InvalidTotpCodeException
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.usecase.auth.GetPortfoliosUseCase
import com.tradingplatform.app.domain.usecase.auth.Verify2faUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TotpViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val verify2faUseCase = mockk<Verify2faUseCase>()
    private val getPortfoliosUseCase = mockk<GetPortfoliosUseCase>()
    private lateinit var viewModel: TotpViewModel

    private val fakePortfolio = Portfolio(id = 42, name = "Main Portfolio", currency = "EUR")
    private val sessionToken = "session-token-xyz"
    private val validCode = "123456"

    @Before
    fun setUp() {
        viewModel = TotpViewModel(verify2faUseCase, getPortfoliosUseCase)
    }

    // ── Cas nominal : vérification réussie → Success ───────────────────────────

    @Test
    fun `verify success emits Verifying then Success`() = runTest {
        coEvery { verify2faUseCase(any(), any()) } returns Result.success(Unit)
        coEvery { getPortfoliosUseCase() } returns Result.success(listOf(fakePortfolio))

        viewModel.uiState.test {
            assertEquals(TotpUiState.AwaitingInput, awaitItem()) // état initial

            viewModel.verify(sessionToken, validCode)

            assertEquals(TotpUiState.Verifying, awaitItem())
            assertEquals(TotpUiState.Success, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Code TOTP invalide (InvalidTotpCodeException) ─────────────────────────

    @Test
    fun `verify returns Error on InvalidTotpCodeException`() = runTest {
        coEvery { verify2faUseCase(any(), any()) } returns
            Result.failure(InvalidTotpCodeException())

        viewModel.uiState.test {
            assertEquals(TotpUiState.AwaitingInput, awaitItem())

            viewModel.verify(sessionToken, "999999")

            assertEquals(TotpUiState.Verifying, awaitItem())
            val state = awaitItem()
            assertTrue(state is TotpUiState.Error)
            assertEquals("Code incorrect. Réessayez.", (state as TotpUiState.Error).message)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Erreur générique de vérification ──────────────────────────────────────

    @Test
    fun `verify returns Error on unexpected exception`() = runTest {
        coEvery { verify2faUseCase(any(), any()) } returns
            Result.failure(RuntimeException("Server error"))

        viewModel.uiState.test {
            assertEquals(TotpUiState.AwaitingInput, awaitItem())

            viewModel.verify(sessionToken, validCode)

            assertEquals(TotpUiState.Verifying, awaitItem())
            val state = awaitItem()
            assertTrue(state is TotpUiState.Error)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Portfolio vide après vérification réussie ──────────────────────────────

    @Test
    fun `verify emits Error when portfolios empty after successful 2FA`() = runTest {
        coEvery { verify2faUseCase(any(), any()) } returns Result.success(Unit)
        coEvery { getPortfoliosUseCase() } returns Result.success(emptyList())

        viewModel.uiState.test {
            assertEquals(TotpUiState.AwaitingInput, awaitItem())

            viewModel.verify(sessionToken, validCode)

            assertEquals(TotpUiState.Verifying, awaitItem())
            val state = awaitItem()
            assertTrue(state is TotpUiState.Error)
            assertNotNull((state as TotpUiState.Error).message)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Erreur réseau GetPortfoliosUseCase ─────────────────────────────────────

    @Test
    fun `verify emits Error when getPortfoliosUseCase fails after successful 2FA`() = runTest {
        coEvery { verify2faUseCase(any(), any()) } returns Result.success(Unit)
        coEvery { getPortfoliosUseCase() } returns Result.failure(RuntimeException("Network error"))

        viewModel.uiState.test {
            assertEquals(TotpUiState.AwaitingInput, awaitItem())

            viewModel.verify(sessionToken, validCode)

            assertEquals(TotpUiState.Verifying, awaitItem())
            val state = awaitItem()
            assertTrue(state is TotpUiState.Error)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── resetError ────────────────────────────────────────────────────────────

    @Test
    fun `resetError resets state from Error to AwaitingInput`() = runTest {
        coEvery { verify2faUseCase(any(), any()) } returns
            Result.failure(InvalidTotpCodeException())

        viewModel.uiState.test {
            assertEquals(TotpUiState.AwaitingInput, awaitItem())

            viewModel.verify(sessionToken, "000000")

            assertEquals(TotpUiState.Verifying, awaitItem())
            assertTrue(awaitItem() is TotpUiState.Error)

            viewModel.resetError()

            assertEquals(TotpUiState.AwaitingInput, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `resetError is no-op when state is not Error`() = runTest {
        // État initial AwaitingInput — resetError ne doit rien faire
        viewModel.uiState.test {
            assertEquals(TotpUiState.AwaitingInput, awaitItem())

            viewModel.resetError()

            // Aucun nouvel état émis
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Idempotence Verifying ──────────────────────────────────────────────────

    @Test
    fun `second verify call is ignored while Verifying`() = runTest {
        coEvery { verify2faUseCase(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(100)
            Result.success(Unit)
        }
        coEvery { getPortfoliosUseCase() } returns Result.success(listOf(fakePortfolio))

        viewModel.uiState.test {
            assertEquals(TotpUiState.AwaitingInput, awaitItem())

            viewModel.verify(sessionToken, validCode)
            assertEquals(TotpUiState.Verifying, awaitItem())

            // Second appel pendant Verifying — ignoré
            viewModel.verify(sessionToken, validCode)

            // On attend Success directement (pas de double Verifying)
            assertEquals(TotpUiState.Success, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
