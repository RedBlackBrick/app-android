package com.tradingplatform.app.ui.screens.settings

import app.cash.turbine.test
import com.tradingplatform.app.domain.model.User
import com.tradingplatform.app.domain.usecase.auth.GetUserProfileUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private val fakeUser = User(
        id = 1L,
        email = "user@example.com",
        firstName = "John",
        lastName = "Doe",
        isAdmin = false,
        totpEnabled = false,
    )

    private fun createViewModel(): ProfileViewModel = ProfileViewModel(
        getUserProfileUseCase = getUserProfileUseCase,
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

    // ── refresh ──────────────────────────────────────────────────────────────

    @Test
    fun `refresh reloads the profile`() = runTest {
        coEvery { getUserProfileUseCase() } returns Result.success(fakeUser)

        val viewModel = createViewModel()
        viewModel.refresh()

        coVerify(exactly = 2) { getUserProfileUseCase() }
    }
}
