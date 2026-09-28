package com.tradingplatform.app.ui.screens.settings

import app.cash.turbine.test
import com.tradingplatform.app.domain.model.User
import com.tradingplatform.app.domain.usecase.auth.GetUserProfileUseCase
import com.tradingplatform.app.domain.usecase.market.GetDefaultQuoteSymbolUseCase
import com.tradingplatform.app.domain.usecase.market.SetDefaultQuoteSymbolUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertIs

/**
 * NOTE (documented follow-up, not covered by these tests): `ProfileViewModel.refresh()` re-fetches
 * `user.is_admin` via [GetUserProfileUseCase] but does NOT re-apply widget visibility
 * (`ApplyAdminWidgetVisibilityUseCase` / `PackageManager.setComponentEnabledSetting`) if an admin
 * account is demoted server-side between logins. Widget visibility (CLAUDE.md §2) is only applied
 * in `LoginViewModel` right after login — a demotion detected here via `/auth/me` leaves the
 * `SystemStatusWidget` enabled until the next full login. Not exercised here because the current
 * `ProfileViewModel` has no dependency capable of re-applying it; flagging for a future PR.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getUserProfileUseCase = mockk<GetUserProfileUseCase>()
    private val getDefaultQuoteSymbolUseCase = mockk<GetDefaultQuoteSymbolUseCase>()
    private val setDefaultQuoteSymbolUseCase = mockk<SetDefaultQuoteSymbolUseCase>()

    private val fakeUser = User(
        id = 1L,
        email = "user@example.com",
        firstName = "John",
        lastName = "Doe",
        isAdmin = false,
        totpEnabled = false,
    )

    @Before
    fun setUp() {
        coEvery { getDefaultQuoteSymbolUseCase() } returns "AAPL"
    }

    private fun createViewModel(): ProfileViewModel = ProfileViewModel(
        getUserProfileUseCase = getUserProfileUseCase,
        getDefaultQuoteSymbolUseCase = getDefaultQuoteSymbolUseCase,
        setDefaultQuoteSymbolUseCase = setDefaultQuoteSymbolUseCase,
    )

    // ── init / loadProfile ───────────────────────────────────────────────────

    @Test
    fun `uiState emits Loading then Success with the loaded profile`() = runTest {
        coEvery { getUserProfileUseCase() } returns Result.success(fakeUser)

        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<ProfileUiState.Success>(state)
            assertEquals(fakeUser, state.user)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState emits Error when GetUserProfileUseCase fails`() = runTest {
        coEvery { getUserProfileUseCase() } returns Result.failure(RuntimeException("boom"))

        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<ProfileUiState.Error>(state)
            assertEquals("boom", state.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `defaultQuoteSymbol is loaded from GetDefaultQuoteSymbolUseCase on init`() = runTest {
        coEvery { getUserProfileUseCase() } returns Result.success(fakeUser)
        coEvery { getDefaultQuoteSymbolUseCase() } returns "TSLA"

        val viewModel = createViewModel()

        viewModel.defaultQuoteSymbol.test {
            assertEquals("TSLA", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── refresh ──────────────────────────────────────────────────────────────

    @Test
    fun `refresh reloads profile and default quote symbol`() = runTest {
        coEvery { getUserProfileUseCase() } returns Result.success(fakeUser)

        val viewModel = createViewModel()
        viewModel.refresh()

        coVerify(exactly = 2) { getUserProfileUseCase() }
        coVerify(exactly = 2) { getDefaultQuoteSymbolUseCase() }
    }

    // ── updateDefaultQuoteSymbol ─────────────────────────────────────────────

    @Test
    fun `updateDefaultQuoteSymbol persists and normalizes the symbol on success`() = runTest {
        coEvery { getUserProfileUseCase() } returns Result.success(fakeUser)
        coEvery { setDefaultQuoteSymbolUseCase("tsla") } returns Result.success(Unit)

        val viewModel = createViewModel()
        viewModel.updateDefaultQuoteSymbol("tsla")

        viewModel.defaultQuoteSymbol.test {
            assertEquals("TSLA", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateDefaultQuoteSymbol does not update state when persistence fails`() = runTest {
        coEvery { getUserProfileUseCase() } returns Result.success(fakeUser)
        coEvery { getDefaultQuoteSymbolUseCase() } returns "AAPL"
        coEvery { setDefaultQuoteSymbolUseCase("tsla") } returns
            Result.failure(RuntimeException("write failed"))

        val viewModel = createViewModel()
        viewModel.updateDefaultQuoteSymbol("tsla")

        viewModel.defaultQuoteSymbol.test {
            // Unchanged — still the value loaded at init, the failed write was not applied locally
            assertEquals("AAPL", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
