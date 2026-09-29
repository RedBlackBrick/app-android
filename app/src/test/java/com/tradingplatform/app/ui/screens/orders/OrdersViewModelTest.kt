package com.tradingplatform.app.ui.screens.orders

import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.Order
import com.tradingplatform.app.domain.model.OrderSide
import com.tradingplatform.app.domain.model.OrderStatus
import com.tradingplatform.app.domain.model.OrderType
import com.tradingplatform.app.domain.model.Page
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.usecase.orders.CancelOrderUseCase
import com.tradingplatform.app.domain.usecase.orders.GetActiveOrdersUseCase
import com.tradingplatform.app.domain.usecase.orders.GetOrderHistoryUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import com.tradingplatform.app.domain.usecase.write.EvaluateWriteGateUseCase
import com.tradingplatform.app.domain.usecase.write.WriteBlockReason
import com.tradingplatform.app.domain.usecase.write.WriteGate
import com.tradingplatform.app.util.MainDispatcherRule
import com.tradingplatform.app.vpn.VpnNotConnectedException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

/**
 * Covers findings C-orders-corr-2 (audit fetches with a possibly empty portfolioId while
 * `refresh` guards it — init should guard the same way) and C-orders-corr-3 (history capped
 * at 50, `count` discarded, no pagination) from audit/candidates-C-ui-vpn.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrdersViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val observeActivePortfolioUseCase = mockk<ObserveActivePortfolioUseCase>()
    private val getActiveOrdersUseCase = mockk<GetActiveOrdersUseCase>()
    private val getOrderHistoryUseCase = mockk<GetOrderHistoryUseCase>()
    private val cancelOrderUseCase = mockk<CancelOrderUseCase>()
    private val evaluateWriteGateUseCase = mockk<EvaluateWriteGateUseCase>()

    /** Portefeuille actif simulé : modifier `.value` équivaut à un changement de sélection. */
    private val activePortfolio = MutableStateFlow("1")

    private lateinit var viewModel: OrdersViewModel

    @Before
    fun setUp() {
        every { observeActivePortfolioUseCase() } returns activePortfolio
        // Garde d'écriture ouverte par défaut ; les tests d'annulation la referment au besoin.
        every { evaluateWriteGateUseCase(any(), any()) } returns WriteGate.Allowed
    }

    private fun order(id: Long, status: OrderStatus = OrderStatus.FILLED): Order = Order(
        id = id,
        symbol = "TSLA",
        side = OrderSide.BUY,
        quantity = BigDecimal("1"),
        orderType = OrderType.MARKET,
        status = status,
        filledQuantity = BigDecimal("1"),
        averageFillPrice = BigDecimal("250.00"),
        limitPrice = null,
        stopPrice = null,
        portfolioId = "1",
        brokerOrderId = null,
        createdAt = null,
        updatedAt = null,
    )

    private fun createViewModel(): OrdersViewModel = OrdersViewModel(
        observeActivePortfolioUseCase = observeActivePortfolioUseCase,
        getActiveOrdersUseCase = getActiveOrdersUseCase,
        getOrderHistoryUseCase = getOrderHistoryUseCase,
        cancelOrderUseCase = cancelOrderUseCase,
        evaluateWriteGateUseCase = evaluateWriteGateUseCase,
    )

    // ── Blank portfolioId guard (C-orders-corr-2) ────────────────────────────────

    @Test
    fun `blank portfolioId sets Error on both tabs without calling the API`() = runTest {
        activePortfolio.value = ""

        viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue(state.active is OrdersTabState.Error)
        assertTrue(state.history is OrdersTabState.Error)
        assertEquals(
            "Portfolio introuvable",
            (state.active as OrdersTabState.Error).message,
        )
        assertEquals(
            "Portfolio introuvable",
            (state.history as OrdersTabState.Error).message,
        )
        coVerify(exactly = 0) { getActiveOrdersUseCase(any()) }
        coVerify(exactly = 0) { getOrderHistoryUseCase(any(), any(), any()) }
    }

    @Test
    fun `refresh is a no-op when portfolioId never resolved`() = runTest {
        activePortfolio.value = ""
        viewModel = createViewModel()

        viewModel.refresh()

        coVerify(exactly = 0) { getActiveOrdersUseCase(any()) }
        coVerify(exactly = 0) { getOrderHistoryUseCase(any(), any(), any()) }
    }

    // ── History pagination (C-orders-corr-3) ─────────────────────────────────────

    @Test
    fun `history hasMore is derived from the backend count, not page fullness`() = runTest {
        coEvery { getActiveOrdersUseCase(any()) } returns Result.success(emptyList())
        coEvery { getOrderHistoryUseCase(any(), any(), 0) } returns
            Result.success(Page(items = listOf(order(1), order(2)), total = 10))

        viewModel = createViewModel()

        val history = viewModel.uiState.value.history as OrdersTabState.Success
        assertEquals(2, history.orders.size)
        assertTrue("2 of 10 total loaded — hasMore must be true", history.hasMore)
    }

    @Test
    fun `loadMoreHistory appends the next page and clears hasMore once exhausted`() = runTest {
        coEvery { getActiveOrdersUseCase(any()) } returns Result.success(emptyList())
        coEvery { getOrderHistoryUseCase(any(), any(), 0) } returns
            Result.success(Page(items = listOf(order(1), order(2)), total = 3))
        coEvery { getOrderHistoryUseCase(any(), any(), 2) } returns
            Result.success(Page(items = listOf(order(3)), total = 3))

        viewModel = createViewModel()
        viewModel.loadMoreHistory()

        val history = viewModel.uiState.value.history as OrdersTabState.Success
        assertEquals(listOf(1L, 2L, 3L), history.orders.map { it.id })
        assertFalse("All 3 of 3 loaded — no more pages", history.hasMore)
        assertFalse(history.isLoadingMore)
        coVerify(exactly = 1) { getOrderHistoryUseCase(any(), any(), 2) }
    }

    @Test
    fun `double loadMoreHistory before the first completes triggers only one use-case call`() = runTest {
        coEvery { getActiveOrdersUseCase(any()) } returns Result.success(emptyList())
        coEvery { getOrderHistoryUseCase(any(), any(), 0) } returns
            Result.success(Page(items = listOf(order(1), order(2)), total = 10))

        viewModel = createViewModel()

        val deferred = CompletableDeferred<Result<Page<Order>>>()
        coEvery { getOrderHistoryUseCase(any(), any(), 2) } coAnswers { deferred.await() }

        viewModel.loadMoreHistory()
        viewModel.loadMoreHistory() // must no-op: a history load is already active

        deferred.complete(Result.success(Page(items = listOf(order(3)), total = 10)))

        coVerify(exactly = 1) { getOrderHistoryUseCase(any(), any(), 2) }
    }

    // ── Changement de portefeuille actif ─────────────────────────────────────────

    @Test
    fun `changing the active portfolio resets both tabs and pagination then reloads with the new id`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { getActiveOrdersUseCase("p1") } returns Result.success(listOf(order(1)))
        coEvery { getOrderHistoryUseCase("p1", any(), 0) } returns
            Result.success(Page(items = listOf(order(1), order(2)), total = 10))
        coEvery { getActiveOrdersUseCase("p2") } coAnswers {
            gate.await()
            Result.success(listOf(order(11)))
        }
        coEvery { getOrderHistoryUseCase("p2", any(), 0) } coAnswers {
            gate.await()
            Result.success(Page(items = listOf(order(12)), total = 5))
        }
        coEvery { getOrderHistoryUseCase("p2", any(), 1) } returns
            Result.success(Page(items = listOf(order(13)), total = 5))
        activePortfolio.value = "p1"
        viewModel = createViewModel()
        val loaded = viewModel.uiState.value
        assertEquals(listOf(1L, 2L), (loaded.history as OrdersTabState.Success).orders.map { it.id })
        viewModel.selectTab(OrdersTab.HISTORY)

        activePortfolio.value = "p2"

        // Rien de p1 ne reste visible pendant le rechargement ; l'onglet choisi est conservé.
        val during = viewModel.uiState.value
        assertEquals("p2", during.portfolioId)
        assertTrue("Expected active Loading, got ${during.active}", during.active is OrdersTabState.Loading)
        assertTrue("Expected history Loading, got ${during.history}", during.history is OrdersTabState.Loading)
        assertEquals(OrdersTab.HISTORY, during.selectedTab)

        gate.complete(Unit)

        val after = viewModel.uiState.value
        assertEquals(listOf(11L), (after.active as OrdersTabState.Success).orders.map { it.id })
        val history = after.history as OrdersTabState.Success
        assertEquals(listOf(12L), history.orders.map { it.id })
        assertTrue(history.hasMore)
        coVerify(exactly = 1) { getActiveOrdersUseCase("p2") }
        coVerify(exactly = 1) { getOrderHistoryUseCase("p2", any(), 0) }

        // L'offset d'historique est reparti de zéro : la page suivante de p2 commence à 1 (pas à 3).
        viewModel.loadMoreHistory()

        val paged = viewModel.uiState.value.history as OrdersTabState.Success
        assertEquals(listOf(12L, 13L), paged.orders.map { it.id })
        coVerify(exactly = 1) { getOrderHistoryUseCase("p2", any(), 1) }
    }

    @Test
    fun `a refresh response arriving after a portfolio switch is dropped`() = runTest {
        coEvery { getActiveOrdersUseCase("p1") } returns Result.success(listOf(order(1)))
        coEvery { getOrderHistoryUseCase("p1", any(), any()) } returns
            Result.success(Page(items = listOf(order(1)), total = 1))
        coEvery { getActiveOrdersUseCase("p2") } returns Result.success(listOf(order(11)))
        coEvery { getOrderHistoryUseCase("p2", any(), any()) } returns
            Result.success(Page(items = listOf(order(12)), total = 1))
        activePortfolio.value = "p1"
        viewModel = createViewModel()

        val lateP1 = CompletableDeferred<Result<List<Order>>>()
        coEvery { getActiveOrdersUseCase("p1") } coAnswers { lateP1.await() }
        viewModel.refresh() // rafraîchissement de p1 en vol

        activePortfolio.value = "p2"
        lateP1.complete(Result.success(listOf(order(1)))) // réponse tardive de p1

        val active = viewModel.uiState.value.active as OrdersTabState.Success
        assertEquals(listOf(11L), active.orders.map { it.id })
    }

    // ── Annulation d'un ordre actif (docs/write-actions.md) ──────────────────────────────────

    private fun stubEmptyHistory() {
        coEvery { getOrderHistoryUseCase(any(), any(), any()) } returns
            Result.success(Page(items = emptyList(), total = 0))
    }

    /** Ordre actif et annulable. */
    private fun submitted(id: Long): Order = order(id, OrderStatus.SUBMITTED)

    private fun activeIds(state: OrdersUiState): List<Long> =
        (state.active as OrdersTabState.Success).orders.map { it.id }

    @Test
    fun `a blocked write gate opens no confirmation and never calls the cancel use case`() = runTest {
        stubEmptyHistory()
        coEvery { getActiveOrdersUseCase("1") } returns Result.success(listOf(submitted(21)))
        every { evaluateWriteGateUseCase(any(), any()) } returns WriteGate.Blocked(
            WriteBlockReason.VPN_NOT_CONNECTED,
            "VPN requis — activez le tunnel puis réessayez.",
        )
        viewModel = createViewModel()

        viewModel.onCancelClicked(21L)
        viewModel.confirmCancel() // rien n'est en attente : sans effet

        val state = viewModel.uiState.value
        assertNull(state.pendingCancel)
        assertEquals("VPN requis — activez le tunnel puis réessayez.", state.message)
        assertTrue(state.cancelRequestedIds.isEmpty())
        coVerify(exactly = 0) { cancelOrderUseCase(any()) }
    }

    @Test
    fun `the write gate receives the time of the last successful read and a 60 s freshness limit`() = runTest {
        stubEmptyHistory()
        coEvery { getActiveOrdersUseCase("1") } returns Result.success(listOf(submitted(21)))
        val syncedAts = mutableListOf<Long?>()
        val maxAges = mutableListOf<Long>()
        every { evaluateWriteGateUseCase(any(), any()) } answers {
            syncedAts.add(firstArg<Long?>())
            maxAges.add(secondArg<Long>())
            WriteGate.Allowed
        }
        val before = System.currentTimeMillis()
        viewModel = createViewModel()
        val after = System.currentTimeMillis()

        viewModel.onCancelClicked(21L)

        assertEquals(1, syncedAts.size)
        val syncedAt = syncedAts.single()
        assertNotNull(syncedAt)
        assertTrue("syncedAt=$syncedAt not in [$before, $after]", syncedAt!! in before..after)
        assertEquals(listOf(60_000L), maxAges)
    }

    @Test
    fun `an allowed gate opens the confirmation and dismissing it sends nothing`() = runTest {
        stubEmptyHistory()
        coEvery { getActiveOrdersUseCase("1") } returns Result.success(listOf(submitted(21)))
        viewModel = createViewModel()

        viewModel.onCancelClicked(21L)

        assertEquals(submitted(21), viewModel.uiState.value.pendingCancel)
        assertNull(viewModel.uiState.value.message)

        viewModel.dismissCancel()
        viewModel.confirmCancel() // feuille fermée : sans effet

        assertNull(viewModel.uiState.value.pendingCancel)
        assertTrue(viewModel.uiState.value.cancelRequestedIds.isEmpty())
        coVerify(exactly = 0) { cancelOrderUseCase(any()) }
    }

    @Test
    fun `orders that are not cancellable or not listed never reach the write gate`() = runTest {
        stubEmptyHistory()
        coEvery { getActiveOrdersUseCase("1") } returns Result.success(
            listOf(
                order(31, OrderStatus.PENDING_APPROVAL),
                order(32, OrderStatus.PENDING_CANCEL),
                order(33, OrderStatus.FILLED),
            ),
        )
        viewModel = createViewModel()

        viewModel.onCancelClicked(31L)
        viewModel.onCancelClicked(32L)
        viewModel.onCancelClicked(33L)
        viewModel.onCancelClicked(999L)

        assertNull(viewModel.uiState.value.pendingCancel)
        assertNull(viewModel.uiState.value.message)
        verify(exactly = 0) { evaluateWriteGateUseCase(any(), any()) }
    }

    @Test
    fun `a confirmed cancel sends exactly one request, keeps the order listed and blocks another cancel until the re-read`() = runTest {
        stubEmptyHistory()
        val reread = CompletableDeferred<Result<List<Order>>>()
        var reads = 0
        coEvery { getActiveOrdersUseCase("1") } coAnswers {
            if (reads++ == 0) Result.success(listOf(submitted(21), submitted(22))) else reread.await()
        }
        coEvery { cancelOrderUseCase(21L) } returns Result.success(WriteOutcome.CONFIRMED)
        viewModel = createViewModel()
        viewModel.onCancelClicked(21L)

        viewModel.confirmCancel()
        viewModel.confirmCancel() // double appel : sans effet

        // Demande partie, relecture en attente : la ligne est « demandée », l'ordre reste listé.
        val during = viewModel.uiState.value
        assertEquals(21L, during.cancelInFlightId)
        assertEquals(setOf(21L), during.cancelRequestedIds)
        assertEquals("Annulation demandée — vérification en cours", during.message)
        assertNull(during.pendingCancel)
        assertEquals(listOf(21L, 22L), activeIds(during))
        // Une annulation en vol à la fois : un autre tap est ignoré.
        viewModel.onCancelClicked(22L)
        assertNull(viewModel.uiState.value.pendingCancel)
        coVerify(exactly = 1) { cancelOrderUseCase(any()) }

        // Le serveur confirme : 21 n'est plus actif.
        reread.complete(Result.success(listOf(submitted(22))))

        val after = viewModel.uiState.value
        assertNull(after.cancelInFlightId)
        assertTrue(after.cancelRequestedIds.isEmpty())
        assertEquals(listOf(22L), activeIds(after))
        assertEquals("Annulation demandée — l'ordre n'est plus dans les ordres actifs", after.message)
        coVerify(exactly = 1) { cancelOrderUseCase(21L) }
        coVerify(exactly = 2) { getActiveOrdersUseCase("1") }
    }

    @Test
    fun `an unconfirmed outcome re-reads the active orders and keeps an order the server still lists`() = runTest {
        stubEmptyHistory()
        val reread = CompletableDeferred<Result<List<Order>>>()
        var reads = 0
        coEvery { getActiveOrdersUseCase("1") } coAnswers {
            if (reads++ == 0) Result.success(listOf(submitted(21))) else reread.await()
        }
        coEvery { cancelOrderUseCase(21L) } returns Result.success(WriteOutcome.REQUESTED_UNCONFIRMED)
        viewModel = createViewModel()
        viewModel.onCancelClicked(21L)

        viewModel.confirmCancel()

        assertEquals(
            "Annulation demandée — état non confirmé, vérification en cours",
            viewModel.uiState.value.message,
        )
        // Relecture lancée (lecture initiale + relecture), l'ordre n'est PAS retiré de façon optimiste.
        coVerify(exactly = 2) { getActiveOrdersUseCase("1") }
        assertEquals(listOf(21L), activeIds(viewModel.uiState.value))

        reread.complete(Result.success(listOf(submitted(21)))) // le broker n'a rien fait

        val after = viewModel.uiState.value
        assertEquals(listOf(21L), activeIds(after))
        assertTrue(after.cancelRequestedIds.isEmpty())
        assertNull(after.cancelInFlightId)
        assertEquals("Annulation demandée — l'ordre est toujours actif", after.message)
        coVerify(exactly = 1) { cancelOrderUseCase(any()) }
    }

    @Test
    fun `a failed re-read keeps the order marked as requested until a later read succeeds`() = runTest {
        stubEmptyHistory()
        var reads = 0
        coEvery { getActiveOrdersUseCase("1") } coAnswers {
            when (reads++) {
                1 -> Result.failure<List<Order>>(IOException("timeout"))
                else -> Result.success(listOf(submitted(21)))
            }
        }
        coEvery { cancelOrderUseCase(21L) } returns Result.success(WriteOutcome.CONFIRMED)
        viewModel = createViewModel()
        viewModel.onCancelClicked(21L)

        viewModel.confirmCancel()

        val state = viewModel.uiState.value
        assertEquals(
            "Annulation demandée — impossible de vérifier l'état, actualisez la liste.",
            state.message,
        )
        assertEquals(setOf(21L), state.cancelRequestedIds)
        assertNull(state.cancelInFlightId)
        assertEquals(listOf(21L), activeIds(state)) // la liste reste affichée, pas d'écran d'erreur

        viewModel.refresh() // lecture réussie : le serveur fait foi

        assertTrue(viewModel.uiState.value.cancelRequestedIds.isEmpty())
        coVerify(exactly = 1) { cancelOrderUseCase(any()) }
    }

    @Test
    fun `a 409 shows an explicit message, unmarks the order and re-reads the stale list`() = runTest {
        stubEmptyHistory()
        var reads = 0
        coEvery { getActiveOrdersUseCase("1") } coAnswers {
            if (reads++ == 0) Result.success(listOf(submitted(21))) else Result.success(emptyList())
        }
        coEvery { cancelOrderUseCase(21L) } returns Result.failure<WriteOutcome>(
            HttpStatusException(409, "v1/orders/{order_id}/cancel", "Ordre non annulable dans son état actuel"),
        )
        viewModel = createViewModel()
        viewModel.onCancelClicked(21L)

        viewModel.confirmCancel()

        val state = viewModel.uiState.value
        assertEquals("Cet ordre n'est plus annulable — liste actualisée.", state.message)
        assertTrue(state.cancelRequestedIds.isEmpty())
        assertNull(state.cancelInFlightId)
        assertEquals(emptyList<Long>(), activeIds(state))
        coVerify(exactly = 1) { cancelOrderUseCase(21L) }
        coVerify(exactly = 2) { getActiveOrdersUseCase("1") }
    }

    @Test
    fun `a VPN failure explains that the tunnel is required and does not re-read`() = runTest {
        stubEmptyHistory()
        coEvery { getActiveOrdersUseCase("1") } returns Result.success(listOf(submitted(21)))
        coEvery { cancelOrderUseCase(21L) } returns Result.failure<WriteOutcome>(VpnNotConnectedException())
        viewModel = createViewModel()
        viewModel.onCancelClicked(21L)

        viewModel.confirmCancel()

        val state = viewModel.uiState.value
        assertEquals("VPN requis — activez le tunnel puis réessayez.", state.message)
        assertTrue(state.cancelRequestedIds.isEmpty())
        assertNull(state.cancelInFlightId)
        assertEquals(listOf(21L), activeIds(state))
        coVerify(exactly = 1) { cancelOrderUseCase(21L) }
        coVerify(exactly = 1) { getActiveOrdersUseCase("1") }
    }

    @Test
    fun `the write gate is evaluated again right before sending and can still block`() = runTest {
        stubEmptyHistory()
        coEvery { getActiveOrdersUseCase("1") } returns Result.success(listOf(submitted(21)))
        every { evaluateWriteGateUseCase(any(), any()) } returnsMany listOf(
            WriteGate.Allowed,
            WriteGate.Blocked(
                WriteBlockReason.DATA_STALE,
                "Données trop anciennes — actualisez l'écran avant d'agir.",
            ),
        )
        viewModel = createViewModel()
        viewModel.onCancelClicked(21L)
        assertEquals(submitted(21), viewModel.uiState.value.pendingCancel)

        viewModel.confirmCancel() // la biométrie a pris du temps : la donnée est devenue périmée

        val state = viewModel.uiState.value
        assertNull(state.pendingCancel)
        assertNull(state.cancelInFlightId)
        assertTrue(state.cancelRequestedIds.isEmpty())
        assertEquals("Données trop anciennes — actualisez l'écran avant d'agir.", state.message)
        coVerify(exactly = 0) { cancelOrderUseCase(any()) }
        verify(exactly = 2) { evaluateWriteGateUseCase(any(), any()) }
    }

    @Test
    fun `switching portfolio forgets a pending confirmation and the freshness used by the gate`() = runTest {
        stubEmptyHistory()
        val p2Gate = CompletableDeferred<Unit>()
        coEvery { getActiveOrdersUseCase("p1") } returns Result.success(listOf(submitted(21)))
        coEvery { getActiveOrdersUseCase("p2") } coAnswers {
            p2Gate.await()
            Result.success(listOf(submitted(51)))
        }
        activePortfolio.value = "p1"
        viewModel = createViewModel()
        viewModel.onCancelClicked(21L)
        assertNotNull(viewModel.uiState.value.pendingCancel)
        assertNotNull(viewModel.uiState.value.activeSyncedAt)

        activePortfolio.value = "p2"

        val during = viewModel.uiState.value
        assertNull(during.pendingCancel)
        assertNull(during.activeSyncedAt)
        assertTrue(during.cancelRequestedIds.isEmpty())
        assertTrue("Expected active Loading, got ${during.active}", during.active is OrdersTabState.Loading)
        viewModel.confirmCancel() // plus rien en attente : aucune requête
        coVerify(exactly = 0) { cancelOrderUseCase(any()) }

        p2Gate.complete(Unit)

        assertNotNull(viewModel.uiState.value.activeSyncedAt)
        assertEquals(listOf(51L), activeIds(viewModel.uiState.value))
    }

    @Test
    fun `a cancel already sent completes after a portfolio switch without touching the new list`() = runTest {
        stubEmptyHistory()
        coEvery { getActiveOrdersUseCase("p1") } returns Result.success(listOf(submitted(21)))
        coEvery { getActiveOrdersUseCase("p2") } returns Result.success(listOf(submitted(51)))
        val sent = CompletableDeferred<Result<WriteOutcome>>()
        coEvery { cancelOrderUseCase(21L) } coAnswers { sent.await() }
        activePortfolio.value = "p1"
        viewModel = createViewModel()
        viewModel.onCancelClicked(21L)
        viewModel.confirmCancel()
        assertEquals(21L, viewModel.uiState.value.cancelInFlightId)

        activePortfolio.value = "p2"

        // La marque « annulation demandée » de p1 ne suit pas dans p2 ; la requête, elle, reste en vol.
        assertTrue(viewModel.uiState.value.cancelRequestedIds.isEmpty())
        assertEquals(listOf(51L), activeIds(viewModel.uiState.value))
        assertEquals(21L, viewModel.uiState.value.cancelInFlightId)

        sent.complete(Result.success(WriteOutcome.CONFIRMED))

        val after = viewModel.uiState.value
        assertNull(after.cancelInFlightId)
        assertEquals("p2", after.portfolioId)
        assertEquals(listOf(51L), activeIds(after)) // la relecture de p1 est écartée
        assertEquals("Annulation demandée — vérification en cours", after.message)
        coVerify(exactly = 1) { cancelOrderUseCase(21L) }
    }

    @Test
    fun `onMessageShown clears only the message it was given`() = runTest {
        stubEmptyHistory()
        coEvery { getActiveOrdersUseCase("1") } returns Result.success(listOf(submitted(21)))
        every { evaluateWriteGateUseCase(any(), any()) } returns WriteGate.Blocked(
            WriteBlockReason.NOT_LOGGED_IN,
            "Session expirée — reconnectez-vous.",
        )
        viewModel = createViewModel()
        viewModel.onCancelClicked(21L)

        viewModel.onMessageShown("un autre message")
        assertEquals("Session expirée — reconnectez-vous.", viewModel.uiState.value.message)

        viewModel.onMessageShown("Session expirée — reconnectez-vous.")
        assertNull(viewModel.uiState.value.message)
    }
}
