package com.tradingplatform.app.ui.screens.alerts

import app.cash.turbine.test
import com.tradingplatform.app.domain.model.Alert
import com.tradingplatform.app.domain.model.AlertType
import com.tradingplatform.app.domain.model.InboxNotification
import com.tradingplatform.app.domain.usecase.alerts.GetAlertsUseCase
import com.tradingplatform.app.domain.usecase.alerts.GetFilteredAlertsUseCase
import com.tradingplatform.app.domain.usecase.alerts.GetInboxUnreadCountUseCase
import com.tradingplatform.app.domain.usecase.alerts.GetInboxUseCase
import com.tradingplatform.app.domain.usecase.alerts.MarkAlertReadUseCase
import com.tradingplatform.app.domain.usecase.alerts.MarkAllAlertsReadUseCase
import com.tradingplatform.app.domain.usecase.alerts.MarkAllInboxReadUseCase
import com.tradingplatform.app.domain.usecase.alerts.MarkInboxReadUseCase
import com.tradingplatform.app.domain.usecase.auth.AuthContext
import com.tradingplatform.app.domain.usecase.auth.GetAuthContextUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import com.tradingplatform.app.vpn.VpnNotConnectedException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AlertsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getAlertsUseCase = mockk<GetAlertsUseCase>()
    private val getFilteredAlertsUseCase = mockk<GetFilteredAlertsUseCase>()
    private val markAlertReadUseCase = mockk<MarkAlertReadUseCase>()
    private val markAllAlertsReadUseCase = mockk<MarkAllAlertsReadUseCase>()
    private val getAuthContextUseCase = mockk<GetAuthContextUseCase>()
    private val getInboxUseCase = mockk<GetInboxUseCase>()
    private val getInboxUnreadCountUseCase = mockk<GetInboxUnreadCountUseCase>()
    private val markInboxReadUseCase = mockk<MarkInboxReadUseCase>()
    private val markAllInboxReadUseCase = mockk<MarkAllInboxReadUseCase>()

    private lateinit var viewModel: AlertsViewModel

    private fun createViewModel(): AlertsViewModel = AlertsViewModel(
        getAlertsUseCase = getAlertsUseCase,
        getFilteredAlertsUseCase = getFilteredAlertsUseCase,
        markAlertReadUseCase = markAlertReadUseCase,
        markAllAlertsReadUseCase = markAllAlertsReadUseCase,
        getAuthContextUseCase = getAuthContextUseCase,
        getInboxUseCase = getInboxUseCase,
        getInboxUnreadCountUseCase = getInboxUnreadCountUseCase,
        markInboxReadUseCase = markInboxReadUseCase,
        markAllInboxReadUseCase = markAllInboxReadUseCase,
    )

    private fun authContext(isAdmin: Boolean) = AuthContext(
        isLoggedIn = true,
        isAdmin = isAdmin,
        setupCompleted = true,
    )

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

    private fun notif(id: String, read: Boolean) = InboxNotification(
        id = id,
        type = "risk_alert",
        title = "Titre $id",
        body = "Corps $id",
        read = read,
        createdAt = Instant.parse("2026-09-29T08:14:03Z"),
    )

    /** n1 et n3 non lues, n2 lue. */
    private val inboxItems = listOf(notif("n1", read = false), notif("n2", read = true), notif("n3", read = false))

    // ── setUp ──────────────────────────────────────────────────────────────────

    @Before
    fun setUp() {
        // Default: stub markAlertReadUseCase so it does not throw on any call
        coEvery { markAlertReadUseCase(any()) } returns Result.success(Unit)
        coEvery { markAllAlertsReadUseCase() } returns Result.success(Unit)
        // Default: standard (non-admin) account
        coEvery { getAuthContextUseCase() } returns authContext(isAdmin = false)
        // Default: server inbox reachable, 2 unread, mark-read calls succeed
        coEvery { getInboxUseCase() } returns Result.success(inboxItems)
        coEvery { getInboxUnreadCountUseCase() } returns Result.success(2)
        coEvery { markInboxReadUseCase(any()) } returns Result.success(Unit)
        coEvery { markAllInboxReadUseCase() } returns Result.success(Unit)
    }

    // ── Loading → Success transition ───────────────────────────────────────────

    @Test
    fun `initial state is Loading`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        viewModel = createViewModel()

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
        viewModel = createViewModel()

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
        viewModel = createViewModel()

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
        viewModel = createViewModel()

        viewModel.uiState.test {
            val state = awaitItem() as AlertsUiState.Success
            assertEquals(0, state.unreadCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unreadCount reflects number of unread alerts`() = runTest {
        every { getAlertsUseCase() } returns flowOf(listOf(unreadAlert, readAlert, criticalAlert))
        viewModel = createViewModel()

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
        viewModel = createViewModel()

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
        viewModel = createViewModel()

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
    fun `uiState emits Error when Flow keeps throwing after all retries`() = runTest {
        every { getAlertsUseCase() } returns kotlinx.coroutines.flow.flow {
            throw RuntimeException("Room error")
        }
        viewModel = createViewModel()

        viewModel.uiState.test {
            // Loading while the retries (virtual-time backoff) are pending
            assertEquals(AlertsUiState.Loading, awaitItem())
            val state = awaitItem() as AlertsUiState.Error
            assertEquals("Room error", state.message)
            cancelAndIgnoreRemainingEvents()
        }
        // 1 initial subscription + MAX_RETRIES re-subscriptions
        verify(exactly = 1 + AlertsViewModel.MAX_RETRIES.toInt()) { getAlertsUseCase() }
    }

    @Test
    fun `transient upstream error is retried and Success is re-emitted`() = runTest {
        var subscriptions = 0
        every { getAlertsUseCase() } answers {
            kotlinx.coroutines.flow.flow {
                subscriptions++
                if (subscriptions == 1) {
                    emit(listOf(unreadAlert))
                    throw RuntimeException("SQLiteException: database is locked")
                }
                emit(listOf(unreadAlert, readAlert))
            }
        }
        viewModel = createViewModel()

        viewModel.uiState.test {
            val first = awaitItem() as AlertsUiState.Success
            assertEquals(1, first.alerts.size)

            // No Error in between — the retry recovers transparently
            val recovered = awaitItem() as AlertsUiState.Success
            assertEquals(2, recovered.alerts.size)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, subscriptions)
    }

    @Test
    fun `filter change after an error re-subscribes`() = runTest {
        every { getAlertsUseCase() } returns kotlinx.coroutines.flow.flow {
            throw RuntimeException("Room error")
        }
        every { getFilteredAlertsUseCase(setOf(AlertType.PRICE_ALERT)) } returns
            flowOf(listOf(unreadAlert))
        viewModel = createViewModel()

        viewModel.uiState.test {
            assertEquals(AlertsUiState.Loading, awaitItem())
            assertTrue(awaitItem() is AlertsUiState.Error)

            // Before the fix the outer chain had completed on `.catch`: this was a no-op.
            viewModel.setTypeFilter(setOf(AlertType.PRICE_ALERT))

            val state = awaitItem() as AlertsUiState.Success
            assertEquals(listOf(unreadAlert), state.alerts)
            assertEquals(setOf(AlertType.PRICE_ALERT), state.activeFilter)
            cancelAndIgnoreRemainingEvents()
        }
        verify(exactly = 1) { getFilteredAlertsUseCase(setOf(AlertType.PRICE_ALERT)) }
    }

    // ── markAsRead ─────────────────────────────────────────────────────────────

    @Test
    fun `markAsRead delegates to MarkAlertReadUseCase`() = runTest {
        every { getAlertsUseCase() } returns flowOf(listOf(unreadAlert))
        coEvery { markAlertReadUseCase(1L) } returns Result.success(Unit)
        viewModel = createViewModel()

        viewModel.markAsRead(1L)

        // advanceUntilIdle() drains all coroutines scheduled on the test dispatcher
        advanceUntilIdle()

        coVerify(exactly = 1) { markAlertReadUseCase(1L) }
    }

    @Test
    fun `markAsRead does not crash when UseCase returns failure`() = runTest {
        every { getAlertsUseCase() } returns flowOf(listOf(unreadAlert))
        coEvery { markAlertReadUseCase(any()) } returns Result.failure(RuntimeException("DB error"))
        viewModel = createViewModel()

        // Should not throw — errors are silently swallowed in markAsRead
        viewModel.markAsRead(1L)
        advanceUntilIdle()

        coVerify(exactly = 1) { markAlertReadUseCase(1L) }
    }

    @Test
    fun `markAsRead can be called multiple times for different alerts`() = runTest {
        every { getAlertsUseCase() } returns flowOf(listOf(unreadAlert, criticalAlert))
        viewModel = createViewModel()

        viewModel.markAsRead(1L)
        viewModel.markAsRead(3L)
        advanceUntilIdle()

        coVerify(exactly = 1) { markAlertReadUseCase(1L) }
        coVerify(exactly = 1) { markAlertReadUseCase(3L) }
    }

    // ── markAllAsRead ──────────────────────────────────────────────────────────

    @Test
    fun `markAllAsRead delegates to MarkAllAlertsReadUseCase`() = runTest {
        every { getAlertsUseCase() } returns flowOf(listOf(unreadAlert, criticalAlert))
        viewModel = createViewModel()

        viewModel.markAllAsRead()
        advanceUntilIdle()

        coVerify(exactly = 1) { markAllAlertsReadUseCase() }
        // The single-alert path must not be used for the bulk action
        coVerify(exactly = 0) { markAlertReadUseCase(any()) }
    }

    @Test
    fun `markAllAsRead does not crash when UseCase returns failure`() = runTest {
        every { getAlertsUseCase() } returns flowOf(listOf(unreadAlert))
        coEvery { markAllAlertsReadUseCase() } returns Result.failure(RuntimeException("DB error"))
        viewModel = createViewModel()

        viewModel.markAllAsRead()
        advanceUntilIdle()

        coVerify(exactly = 1) { markAllAlertsReadUseCase() }
        // The list stays available — a failed bulk mark-as-read is non-critical
        assertTrue(viewModel.uiState.value is AlertsUiState.Success)
    }

    // ── availableTypes (filtre selon isAdmin) ──────────────────────────────────

    @Test
    fun `standard account only gets the non-technical filter types`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        coEvery { getAuthContextUseCase() } returns authContext(isAdmin = false)
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(
            listOf(
                AlertType.PRICE_ALERT,
                AlertType.TRADE_EXECUTED,
                AlertType.DEVICE_UNPAIRED,
                AlertType.PORTFOLIO_UPDATE,
                AlertType.UNKNOWN,
            ),
            viewModel.availableTypes.value,
        )
    }

    @Test
    fun `admin account gets every filter type`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        coEvery { getAuthContextUseCase() } returns authContext(isAdmin = true)
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(AlertType.entries.toList(), viewModel.availableTypes.value)
        assertTrue(viewModel.availableTypes.value.containsAll(
            listOf(
                AlertType.SCRAPING_ERROR,
                AlertType.OTA_COMPLETE,
                AlertType.SYSTEM_ERROR,
                AlertType.DEVICE_OFFLINE,
                AlertType.DEVICE_ONLINE,
            ),
        ))
    }

    @Test
    fun `unreadable auth context falls back to the non-admin filter types`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        coEvery { getAuthContextUseCase() } throws RuntimeException("keystore corrupted")
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(filterableAlertTypes(isAdmin = false), viewModel.availableTypes.value)
        assertTrue(AlertType.SYSTEM_ERROR !in viewModel.availableTypes.value)
    }

    @Test
    fun `filterableAlertTypes hides exactly the technical types for non-admin`() {
        val hidden = AlertType.entries.toSet() - filterableAlertTypes(isAdmin = false).toSet()

        assertEquals(
            setOf(
                AlertType.DEVICE_OFFLINE,
                AlertType.DEVICE_ONLINE,
                AlertType.SCRAPING_ERROR,
                AlertType.OTA_COMPLETE,
                AlertType.SYSTEM_ERROR,
            ),
            hidden,
        )
    }

    // ── Segment « Serveur » : sélection et chargement ──────────────────────────

    @Test
    fun `default segment is Cet appareil and the server is not contacted until needed`() = runTest {
        every { getAlertsUseCase() } returns flowOf(listOf(unreadAlert))
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(AlertsSegment.DEVICE, viewModel.selectedSegment.value)
        assertEquals(InboxUiState.Loading, viewModel.inboxState.value)
        assertEquals(0, viewModel.serverUnreadCount.value)
        assertFalse(viewModel.isServerUnreadKnown.value)
        coVerify(exactly = 0) { getInboxUseCase() }
        coVerify(exactly = 0) { getInboxUnreadCountUseCase() }
    }

    @Test
    fun `segment labels are the two expected French labels`() {
        assertEquals(listOf("Cet appareil", "Serveur"), AlertsSegment.entries.map { it.label })
    }

    @Test
    fun `selecting Serveur loads the inbox and the unread count without touching local alerts`() = runTest {
        every { getAlertsUseCase() } returns flowOf(listOf(unreadAlert, readAlert))
        viewModel = createViewModel()

        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()

        assertEquals(AlertsSegment.SERVER, viewModel.selectedSegment.value)
        val inbox = viewModel.inboxState.value as InboxUiState.Success
        assertEquals(listOf("n1", "n2", "n3"), inbox.items.map { it.id })
        assertEquals(2, inbox.unreadCount)
        assertEquals(2, viewModel.serverUnreadCount.value)
        assertTrue(viewModel.isServerUnreadKnown.value)
        // Le segment local garde exactement son état
        val local = viewModel.uiState.value as AlertsUiState.Success
        assertEquals(listOf(unreadAlert, readAlert), local.alerts)
        assertEquals(1, local.unreadCount)
        coVerify(exactly = 1) { getInboxUseCase() }
    }

    @Test
    fun `selecting the already selected segment does not reload the inbox`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        viewModel = createViewModel()

        viewModel.selectSegment(AlertsSegment.SERVER)
        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()
        coVerify(exactly = 1) { getInboxUseCase() }

        // Revenir sur « Cet appareil » puis rouvrir « Serveur » recharge (segment en ligne uniquement)
        viewModel.selectSegment(AlertsSegment.DEVICE)
        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()
        coVerify(exactly = 2) { getInboxUseCase() }
    }

    @Test
    fun `empty server inbox is a Success with no rows`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        coEvery { getInboxUseCase() } returns Result.success(emptyList())
        coEvery { getInboxUnreadCountUseCase() } returns Result.success(0)
        viewModel = createViewModel()

        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()

        assertEquals(InboxUiState.Success(emptyList()), viewModel.inboxState.value)
        assertEquals(0, viewModel.serverUnreadCount.value)
    }

    // ── Segment « Serveur » : échecs ───────────────────────────────────────────

    @Test
    fun `VpnNotConnectedException gives VpnRequired even though it is an IOException`() = runTest {
        every { getAlertsUseCase() } returns flowOf(listOf(unreadAlert))
        coEvery { getInboxUseCase() } returns Result.failure(VpnNotConnectedException())
        coEvery { getInboxUnreadCountUseCase() } returns Result.failure(VpnNotConnectedException())
        viewModel = createViewModel()

        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()

        assertEquals(InboxUiState.VpnRequired, viewModel.inboxState.value)
        // Pas de décompte lu : valeur initiale conservée, non « connue »
        assertEquals(0, viewModel.serverUnreadCount.value)
        assertFalse(viewModel.isServerUnreadKnown.value)
        // Les alertes locales restent affichées hors VPN
        val local = viewModel.uiState.value as AlertsUiState.Success
        assertEquals(listOf(unreadAlert), local.alerts)
    }

    @Test
    fun `plain IOException gives the unreachable message`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        coEvery { getInboxUseCase() } returns Result.failure(IOException("timeout"))
        viewModel = createViewModel()

        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()

        assertEquals(
            InboxUiState.Error("Serveur injoignable. Vérifiez la connexion puis réessayez."),
            viewModel.inboxState.value,
        )
    }

    @Test
    fun `other failure gives the generic error and never leaks the raw message`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        coEvery { getInboxUseCase() } returns Result.failure(RuntimeException("HTTP 500 on v1/notifications"))
        viewModel = createViewModel()

        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()

        assertEquals(
            InboxUiState.Error("Impossible de charger les notifications du serveur."),
            viewModel.inboxState.value,
        )
    }

    @Test
    fun `failed reload replaces the list instead of showing stale data`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        viewModel = createViewModel()
        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()
        assertTrue(viewModel.inboxState.value is InboxUiState.Success)

        coEvery { getInboxUseCase() } returns Result.failure(VpnNotConnectedException())
        viewModel.refreshInbox()
        advanceUntilIdle()

        assertEquals(InboxUiState.VpnRequired, viewModel.inboxState.value)
        assertFalse(viewModel.isInboxRefreshing.value)
    }

    @Test
    fun `retry after a failure loads the list again`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        coEvery { getInboxUseCase() } returns Result.failure(VpnNotConnectedException())
        viewModel = createViewModel()
        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()
        assertEquals(InboxUiState.VpnRequired, viewModel.inboxState.value)

        coEvery { getInboxUseCase() } returns Result.success(inboxItems)
        viewModel.refreshInbox()
        advanceUntilIdle()

        assertEquals(3, (viewModel.inboxState.value as InboxUiState.Success).items.size)
    }

    @Test
    fun `refresh keeps the current list visible while reloading`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        viewModel = createViewModel()
        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()

        val gate = CompletableDeferred<Result<List<InboxNotification>>>()
        coEvery { getInboxUseCase() } coAnswers { gate.await() }
        viewModel.refreshInbox()

        assertTrue(viewModel.isInboxRefreshing.value)
        assertEquals(listOf("n1", "n2", "n3"), (viewModel.inboxState.value as InboxUiState.Success).items.map { it.id })

        gate.complete(Result.success(listOf(notif("n9", read = false))))
        advanceUntilIdle()

        assertFalse(viewModel.isInboxRefreshing.value)
        assertEquals(listOf("n9"), (viewModel.inboxState.value as InboxUiState.Success).items.map { it.id })
    }

    // ── Segment « Serveur » : lecture d'une notification ───────────────────────

    @Test
    fun `markInboxRead updates the row and the count immediately then re-reads the count`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        viewModel = createViewModel()
        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()
        assertEquals(2, viewModel.serverUnreadCount.value)

        val gate = CompletableDeferred<Result<Unit>>()
        coEvery { markInboxReadUseCase("n1") } coAnswers { gate.await() }
        coEvery { getInboxUnreadCountUseCase() } returns Result.success(1)

        viewModel.markInboxRead("n1")

        // Optimiste : avant la réponse du serveur
        val optimistic = viewModel.inboxState.value as InboxUiState.Success
        assertTrue(optimistic.items.first { it.id == "n1" }.read)
        assertEquals(1, optimistic.unreadCount)
        assertEquals(1, viewModel.serverUnreadCount.value)
        coVerify(exactly = 1) { markInboxReadUseCase("n1") }
        // Le décompte n'a pas encore été relu (1 lecture au chargement de la liste)
        coVerify(exactly = 1) { getInboxUnreadCountUseCase() }

        gate.complete(Result.success(Unit))
        advanceUntilIdle()

        // Relecture du compteur après la lecture
        coVerify(exactly = 2) { getInboxUnreadCountUseCase() }
        assertEquals(1, viewModel.serverUnreadCount.value)
        assertTrue((viewModel.inboxState.value as InboxUiState.Success).items.first { it.id == "n1" }.read)
    }

    @Test
    fun `markInboxRead ignores an already read row and an unknown id`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        viewModel = createViewModel()
        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()

        viewModel.markInboxRead("n2") // déjà lue
        viewModel.markInboxRead("inconnu")
        advanceUntilIdle()

        coVerify(exactly = 0) { markInboxReadUseCase(any()) }
        assertEquals(2, viewModel.serverUnreadCount.value)
    }

    @Test
    fun `markInboxRead does nothing while the inbox is not displayed`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        viewModel = createViewModel()

        viewModel.markInboxRead("n1")
        advanceUntilIdle()

        coVerify(exactly = 0) { markInboxReadUseCase(any()) }
    }

    @Test
    fun `markInboxRead failure reverts the row and the count`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        viewModel = createViewModel()
        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()
        // Le décompte ne peut plus être relu : seul le retour arrière compte ici
        coEvery { getInboxUnreadCountUseCase() } returns Result.failure(VpnNotConnectedException())
        coEvery { markInboxReadUseCase("n1") } returns Result.failure(VpnNotConnectedException())

        viewModel.markInboxRead("n1")
        advanceUntilIdle()

        val state = viewModel.inboxState.value as InboxUiState.Success
        assertFalse(state.items.first { it.id == "n1" }.read)
        assertEquals(2, state.unreadCount)
        assertEquals(2, viewModel.serverUnreadCount.value)
    }

    @Test
    fun `a reload does not resurrect a row that was just read locally`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        viewModel = createViewModel()
        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()

        viewModel.markInboxRead("n1")
        advanceUntilIdle()
        // Le serveur renvoie encore n1 non lue (relecture partie avant le traitement du POST)
        coEvery { getInboxUseCase() } returns Result.success(inboxItems)
        viewModel.refreshInbox()
        advanceUntilIdle()

        val state = viewModel.inboxState.value as InboxUiState.Success
        assertTrue(state.items.first { it.id == "n1" }.read)
        assertFalse(state.items.first { it.id == "n3" }.read)
    }

    // ── Segment « Serveur » : tout marquer comme lu ────────────────────────────

    @Test
    fun `markAllInboxRead marks every row read and zeroes the count`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        viewModel = createViewModel()
        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()

        val gate = CompletableDeferred<Result<Unit>>()
        coEvery { markAllInboxReadUseCase() } coAnswers { gate.await() }
        coEvery { getInboxUnreadCountUseCase() } returns Result.success(0)

        viewModel.markAllInboxRead()

        // Optimiste
        val optimistic = viewModel.inboxState.value as InboxUiState.Success
        assertTrue(optimistic.items.all { it.read })
        assertEquals(0, viewModel.serverUnreadCount.value)

        gate.complete(Result.success(Unit))
        advanceUntilIdle()

        coVerify(exactly = 1) { markAllInboxReadUseCase() }
        // Le segment local n'est pas concerné
        coVerify(exactly = 0) { markAllAlertsReadUseCase() }
        coVerify(exactly = 2) { getInboxUnreadCountUseCase() }
        assertEquals(0, viewModel.serverUnreadCount.value)
    }

    @Test
    fun `markAllInboxRead failure restores the unread rows and the count`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        viewModel = createViewModel()
        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()
        coEvery { getInboxUnreadCountUseCase() } returns Result.failure(IOException("offline"))
        coEvery { markAllInboxReadUseCase() } returns Result.failure(IOException("offline"))

        viewModel.markAllInboxRead()
        advanceUntilIdle()

        val state = viewModel.inboxState.value as InboxUiState.Success
        assertEquals(listOf("n1", "n3"), state.items.filter { !it.read }.map { it.id })
        assertEquals(2, viewModel.serverUnreadCount.value)
    }

    @Test
    fun `markAllInboxRead does nothing while the inbox needs the VPN`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        coEvery { getInboxUseCase() } returns Result.failure(VpnNotConnectedException())
        viewModel = createViewModel()
        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()

        viewModel.markAllInboxRead()
        advanceUntilIdle()

        coVerify(exactly = 0) { markAllInboxReadUseCase() }
    }

    // ── Décompte serveur (badge) ───────────────────────────────────────────────

    @Test
    fun `onScreenOpened reads the unread count without loading the list on Cet appareil`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        coEvery { getInboxUnreadCountUseCase() } returns Result.success(4)
        viewModel = createViewModel()

        viewModel.onScreenOpened()
        advanceUntilIdle()

        assertEquals(4, viewModel.serverUnreadCount.value)
        assertTrue(viewModel.isServerUnreadKnown.value)
        coVerify(exactly = 0) { getInboxUseCase() }
    }

    @Test
    fun `onScreenOpened on the Serveur segment reloads the inbox`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        viewModel = createViewModel()
        viewModel.selectSegment(AlertsSegment.SERVER)
        advanceUntilIdle()

        viewModel.onScreenOpened()
        advanceUntilIdle()

        coVerify(exactly = 2) { getInboxUseCase() }
    }

    @Test
    fun `unread count keeps its last value when the VPN drops`() = runTest {
        every { getAlertsUseCase() } returns flowOf(emptyList())
        coEvery { getInboxUnreadCountUseCase() } returns Result.success(4)
        viewModel = createViewModel()
        viewModel.onScreenOpened()
        advanceUntilIdle()
        assertEquals(4, viewModel.serverUnreadCount.value)

        coEvery { getInboxUnreadCountUseCase() } returns Result.failure(VpnNotConnectedException())
        viewModel.onScreenOpened()
        advanceUntilIdle()

        assertEquals(4, viewModel.serverUnreadCount.value)
        assertTrue(viewModel.isServerUnreadKnown.value)
    }
}
