package com.tradingplatform.app.ui.screens.settings

import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.domain.usecase.auth.LogoutUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * [SettingsViewModel] has no `uiState` — `logout()` is a fire-and-forget action that always
 * clears local session state via [SessionManager.notifyForcedLogout], regardless of whether
 * the backend logout call succeeds (CLAUDE.md §12 — "l'utilisateur ne doit jamais rester coincé").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val logoutUseCase = mockk<LogoutUseCase>()
    private val sessionManager = mockk<SessionManager>(relaxed = true)

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        viewModel = SettingsViewModel(logoutUseCase, sessionManager)
    }

    @Test
    fun `logout invokes LogoutUseCase once and notifies forced logout on success`() = runTest {
        coEvery { logoutUseCase() } returns Result.success(Unit)

        viewModel.logout()

        coVerify(exactly = 1) { logoutUseCase() }
        verify(exactly = 1) { sessionManager.notifyForcedLogout() }
    }

    @Test
    fun `logout still notifies forced logout when the backend call fails`() = runTest {
        // Local state (Room, DataStore) is cleared by LogoutUseCase itself regardless of the
        // API result — the user must never be stuck on a stale session because the VPS logout
        // endpoint failed (VPN down, timeout, etc).
        coEvery { logoutUseCase() } returns Result.failure(RuntimeException("network error"))

        viewModel.logout()

        coVerify(exactly = 1) { logoutUseCase() }
        verify(exactly = 1) { sessionManager.notifyForcedLogout() }
    }

    @Test
    fun `logout calls notifyForcedLogout exactly once per invocation`() = runTest {
        coEvery { logoutUseCase() } returns Result.success(Unit)

        viewModel.logout()
        viewModel.logout()

        coVerify(exactly = 2) { logoutUseCase() }
        verify(exactly = 2) { sessionManager.notifyForcedLogout() }
    }
}
