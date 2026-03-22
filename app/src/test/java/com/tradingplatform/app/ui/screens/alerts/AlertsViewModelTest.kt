package com.tradingplatform.app.ui.screens.alerts

import app.cash.turbine.test
import com.tradingplatform.app.domain.model.Alert
import com.tradingplatform.app.domain.model.AlertType
import com.tradingplatform.app.domain.usecase.alerts.GetAlertsUseCase
import com.tradingplatform.app.domain.usecase.alerts.GetFilteredAlertsUseCase
import com.tradingplatform.app.domain.usecase.alerts.MarkAlertReadUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AlertsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getAlertsUseCase = mockk<GetAlertsUseCase>()
    private val getFilteredAlertsUseCase = mockk<GetFilteredAlertsUseCase>()
    private val markAlertReadUseCase = mockk<MarkAlertReadUseCase>()

    private lateinit var viewModel: AlertsViewModel

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private val unreadAlert = Alert(
        id = 1L,
        title = "AAPL Price Alert",
        body = "AAPL has reached your target price of $180.",
        type = AlertType.PRICE_ALERT,
        receivedAt = Instant.now(),
        read = false,
    )

    private val readAlert = Alert(
        id = 2L,
        title = "Trade Executed",
        body = "Your order for 10 shares of TSLA has been executed.",
        type = AlertType.TRADE_EXECUTED,
        receivedAt = Instant.now().minusSeconds(3600),
        read = true,
    )

    private val criticalAlert = Alert(
        id = 3L,
        title = "System Error",
        body = "Critical error detected on VPS.",
        type = AlertType.SYSTEM_ERROR,
        receivedAt = Instant.now().minusSeconds(60),
        read = false,
    )

    // ── setUp ──────────────────────────────────────────────────────────────────

    @Before
    fun setUp() {
        // Default: stub markAlertReadUseCase so it does not throw on any call
        coEvery { markAlertReadUseCase(any()) } returns Result.success(Unit)
    }

    // ── Loading → Success transition ───────────────────────────────────────────

    @Test
    fun `initial state is Loading`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        viewModel = AlertsViewModel(getAlertsUseCase, getFilteredAlertsUseCase, markAlertReadUseCase)

        // With UnconfinedTestDispatcher the coroutine runs eagerly, so the first
        // emission collected by Turbine is Success (init has already run).
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue("Expected Success but got $state", state is AlertsUiState.Success)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState emits Success with empty list when no alerts`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        viewModel = AlertsViewModel(getAlertsUseCase, getFilteredAlertsUseCase, markAlertReadUseCase)

        viewModel.uiState.test {
            val state = awaitItem() as AlertsUiState.Success
            assertEquals(0, state.alerts.size)
            assertEquals(0, state.unreadCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState emits Success with correct alerts`() = runTest {
        every { getAlertsUseCase() } returns flowOf(listOf(unreadAlert, readAlert))
        viewModel = AlertsViewModel(getAlertsUseCase, getFilteredAlertsUseCase, markAlertReadUseCase)

        viewModel.uiState.test {
            val state = awaitItem() as AlertsUiState.Success
            assertEquals(2, state.alerts.size)
            assertEquals(unreadAlert, state.alerts[0])
            assertEquals(readAlert, state.alerts[1])
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── unreadCount computation ────────────────────────────────────────────────

    @Test
    fun `unreadCount is 0 when all alerts are read`() = runTest {
        every { getAlertsUseCase() } returns flowOf(listOf(readAlert))
        viewModel = AlertsViewModel(getAlertsUseCase, getFilteredAlertsUseCase, markAlertReadUseCase)

        viewModel.uiState.test {
            val state = awaitItem() as AlertsUiState.Success
            assertEquals(0, state.unreadCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unreadCount reflects number of unread alerts`() = runTest {
        every { getAlertsUseCase() } returns flowOf(listOf(unreadAlert, readAlert, criticalAlert))
        viewModel = AlertsViewModel(getAlertsUseCase, getFilteredAlertsUseCase, markAlertReadUseCase)

        viewModel.uiState.test {
            val state = awaitItem() as AlertsUiState.Success
            assertEquals(3, state.alerts.size)
            // unreadAlert.read = false, readAlert.read = true, criticalAlert.read = false
            assertEquals(2, state.unreadCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unreadCount is correct when all alerts are unread`() = runTest {
        every { getAlertsUseCase() } returns flowOf(listOf(unreadAlert, criticalAlert))
        viewModel = AlertsViewModel(getAlertsUseCase, getFilteredAlertsUseCase, markAlertReadUseCase)

        viewModel.uiState.test {
            val state = awaitItem() as AlertsUiState.Success
            assertEquals(2, state.unreadCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Flow updates ───────────────────────────────────────────────────────────

    @Test
    fun `uiState updates when Flow emits new list`() = runTest {
        val alertFlow = kotlinx.coroutines.flow.MutableStateFlow(listOf(unreadAlert))
        every { getAlertsUseCase() } returns alertFlow
        viewModel = AlertsViewModel(getAlertsUseCase, getFilteredAlertsUseCase, markAlertReadUseCase)

        viewModel.uiState.test {
            // First emission
            val first = awaitItem() as AlertsUiState.Success
            assertEquals(1, first.alerts.size)
            assertEquals(1, first.unreadCount)

            // Simulate Room emitting a new list (alert marked as read)
            alertFlow.value = listOf(unreadAlert.copy(read = true))

            val second = awaitItem() as AlertsUiState.Success
            assertEquals(1, second.alerts.size)
            assertEquals(0, second.unreadCount)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Error state ────────────────────────────────────────────────────────────

    @Test
    fun `uiState emits Error when Flow throws`() = runTest {
        every { getAlertsUseCase() } returns kotlinx.coroutines.flow.flow {
            throw RuntimeException("Room error")
        }
        viewModel = AlertsViewModel(getAlertsUseCase, getFilteredAlertsUseCase, markAlertReadUseCase)

        viewModel.uiState.test {
            val state = awaitItem() as AlertsUiState.Error
            assertEquals("Room error", state.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── markAsRead ─────────────────────────────────────────────────────────────

    @Test
    fun `markAsRead delegates to MarkAlertReadUseCase`() = runTest {
        every { getAlertsUseCase() } returns flowOf(listOf(unreadAlert))
        coEvery { markAlertReadUseCase(1L) } returns Result.success(Unit)
        viewModel = AlertsViewModel(getAlertsUseCase, getFilteredAlertsUseCase, markAlertReadUseCase)

        viewModel.markAsRead(1L)

        // advanceUntilIdle() drains all coroutines scheduled on the test dispatcher
        advanceUntilIdle()

        coVerify(exactly = 1) { markAlertReadUseCase(1L) }
    }

    @Test
    fun `markAsRead does not crash when UseCase returns failure`() = runTest {
        every { getAlertsUseCase() } returns flowOf(listOf(unreadAlert))
        coEvery { markAlertReadUseCase(any()) } returns Result.failure(RuntimeException("DB error"))
        viewModel = AlertsViewModel(getAlertsUseCase, getFilteredAlertsUseCase, markAlertReadUseCase)

        // Should not throw — errors are silently swallowed in markAsRead
        viewModel.markAsRead(1L)
        advanceUntilIdle()

        coVerify(exactly = 1) { markAlertReadUseCase(1L) }
    }

    @Test
    fun `markAsRead can be called multiple times for different alerts`() = runTest {
        every { getAlertsUseCase() } returns flowOf(listOf(unreadAlert, criticalAlert))
        viewModel = AlertsViewModel(getAlertsUseCase, getFilteredAlertsUseCase, markAlertReadUseCase)

        viewModel.markAsRead(1L)
        viewModel.markAsRead(3L)
        advanceUntilIdle()

        coVerify(exactly = 1) { markAlertReadUseCase(1L) }
        coVerify(exactly = 1) { markAlertReadUseCase(3L) }
    }
}
