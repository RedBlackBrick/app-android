package com.tradingplatform.app.ui.screens.portfolio

import app.cash.turbine.test
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.domain.model.WsUpdate
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionWsUpdatesUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionsUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PositionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getPositionsUseCase = mockk<GetPositionsUseCase>()
    private val observeActivePortfolioUseCase = mockk<ObserveActivePortfolioUseCase>()
    private val getPositionWsUpdatesUseCase = mockk<GetPositionWsUpdatesUseCase>()

    /** Portefeuille actif simulé : modifier `.value` équivaut à un changement de sélection. */
    private val activePortfolio = MutableStateFlow("1")

    private lateinit var viewModel: PositionsViewModel

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private val fakePosition = Position(
        id = 42,
        symbol = "TSLA",
        quantity = BigDecimal("10"),
        avgPrice = BigDecimal("250.00"),
        currentPrice = BigDecimal("280.00"),
        unrealizedPnl = BigDecimal("300.00"),
        unrealizedPnlPercent = 12.0,
        status = PositionStatus.OPEN,
        openedAt = Instant.now(),
    )

    private val fakePositions = listOf(fakePosition)

    private val fakeOpenTsla = Position(
        id = 42,
        symbol = "TSLA",
        quantity = BigDecimal("10"),
        avgPrice = BigDecimal("250.00"),
        currentPrice = BigDecimal("280.00"),
        unrealizedPnl = BigDecimal("300.00"),
        unrealizedPnlPercent = 12.0,
        status = PositionStatus.OPEN,
        openedAt = Instant.now(),
    )

    private val fakeClosedTsla = Position(
        id = 99,
        symbol = "TSLA",
        quantity = BigDecimal("5"),
        avgPrice = BigDecimal("200.00"),
        currentPrice = BigDecimal("210.00"),
        unrealizedPnl = BigDecimal("50.00"),
        unrealizedPnlPercent = 5.0,
        status = PositionStatus.CLOSED,
        openedAt = Instant.now(),
    )

    @Before
    fun setUp() {
        every { observeActivePortfolioUseCase() } returns activePortfolio
        coEvery { getPositionsUseCase(any(), any()) } returns Result.success(fakePositions)
        every { getPositionWsUpdatesUseCase() } returns emptyFlow()
    }

    private fun createViewModel(): PositionsViewModel = PositionsViewModel(
        getPositionsUseCase = getPositionsUseCase,
        observeActivePortfolioUseCase = observeActivePortfolioUseCase,
        getPositionWsUpdatesUseCase = getPositionWsUpdatesUseCase,
    )

    // ── Success ───────────────────────────────────────────────────────────────

    @Test
    fun `uiState emits Success with positions on successful load`() = runTest {
        viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue("Expected Success, got $state", state is PositionsUiState.Success)
        val success = state as PositionsUiState.Success
        assertEquals(fakePositions, success.positions)
    }

    @Test
    fun `syncedAt is set on Success`() = runTest {
        viewModel = createViewModel()
        val state = viewModel.uiState.value as? PositionsUiState.Success
        assertNotNull("Expected Success state", state)
        assertTrue("syncedAt should be positive", state!!.syncedAt > 0L)
    }

    @Test
    fun `success with empty positions list is handled`() = runTest {
        coEvery { getPositionsUseCase(any(), any()) } returns Result.success(emptyList())
        viewModel = createViewModel()
        val state = viewModel.uiState.value
        assertTrue("Expected Success with empty list", state is PositionsUiState.Success)
        assertEquals(emptyList<Position>(), (state as PositionsUiState.Success).positions)
    }

    // ── Error ─────────────────────────────────────────────────────────────────

    @Test
    fun `uiState emits Error on use case failure`() = runTest {
        coEvery { getPositionsUseCase(any(), any()) } returns
            Result.failure(RuntimeException("Network error"))
        viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue("Expected Error, got $state", state is PositionsUiState.Error)
    }

    @Test
    fun `error message is propagated from exception`() = runTest {
        coEvery { getPositionsUseCase(any(), any()) } returns
            Result.failure(RuntimeException("Connexion refusée"))
        viewModel = createViewModel()

        val state = viewModel.uiState.value as? PositionsUiState.Error
        assertNotNull("Expected Error state", state)
        assertEquals("Connexion refusée", state!!.message)
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    @Test
    fun `refresh re-fetches positions from use case`() = runTest {
        viewModel = createViewModel()

        viewModel.refresh()

        // Use case should have been called at least twice: once on init, once on refresh
        coVerify(atLeast = 2) { getPositionsUseCase(any(), any()) }
    }

    @Test
    fun `refresh emits Loading then Success`() = runTest {
        viewModel = createViewModel()

        viewModel.uiState.test {
            // Skip any already-emitted items from init
            while (awaitItem() !is PositionsUiState.Success) { /* skip */ }

            viewModel.refresh()

            // After refresh: Loading, then Success
            val afterRefresh = awaitItem()
            assertTrue(
                "Expected Loading or Success after refresh, got $afterRefresh",
                afterRefresh is PositionsUiState.Loading || afterRefresh is PositionsUiState.Success,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh recovers from previous error`() = runTest {
        // First call fails
        coEvery { getPositionsUseCase(any(), any()) } returnsMany listOf(
            Result.failure(RuntimeException("First error")),
            Result.success(fakePositions),
        )

        viewModel = createViewModel()

        // Initial state should be Error
        assertTrue(viewModel.uiState.value is PositionsUiState.Error)

        // Refresh should recover
        viewModel.refresh()

        assertTrue(
            "Expected Success after refresh, got ${viewModel.uiState.value}",
            viewModel.uiState.value is PositionsUiState.Success,
        )
    }

    // ── Refresh : la liste reste affichée (pull-to-refresh) ────────────────────

    /** Premier appel immédiat, appels suivants suspendus sur [gate] puis [then]. */
    private fun givenFirstLoadThenGated(gate: CompletableDeferred<Unit>, then: Result<List<Position>>) {
        var calls = 0
        coEvery { getPositionsUseCase(any(), any()) } coAnswers {
            if (calls++ == 0) {
                Result.success(fakePositions)
            } else {
                gate.await()
                then
            }
        }
    }

    @Test
    fun `refresh keeps the list on screen and flags isRefreshing while the fetch is in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        givenFirstLoadThenGated(gate, Result.success(fakePositions))
        viewModel = createViewModel()
        assertTrue(viewModel.uiState.value is PositionsUiState.Success)

        viewModel.refresh()

        val during = viewModel.uiState.value
        assertTrue("Expected Success while refreshing, got $during", during is PositionsUiState.Success)
        during as PositionsUiState.Success
        assertTrue(during.isRefreshing)
        assertEquals(fakePositions, during.positions) // pas de retour au skeleton

        gate.complete(Unit)

        val after = viewModel.uiState.value as PositionsUiState.Success
        assertEquals(false, after.isRefreshing)
        assertEquals(null, after.refreshError)
    }

    @Test
    fun `refresh failure keeps the stale list and reports the error instead of blanking the screen`() = runTest {
        val gate = CompletableDeferred<Unit>()
        givenFirstLoadThenGated(gate, Result.failure(RuntimeException("VPS injoignable")))
        viewModel = createViewModel()
        val loaded = viewModel.uiState.value as PositionsUiState.Success

        viewModel.refresh()
        gate.complete(Unit)

        val after = viewModel.uiState.value
        assertTrue("Expected the stale list to stay, got $after", after is PositionsUiState.Success)
        after as PositionsUiState.Success
        assertEquals(fakePositions, after.positions)
        assertEquals(loaded.syncedAt, after.syncedAt) // horodatage réel de la valeur périmée
        assertEquals(false, after.isRefreshing)
        assertEquals("VPS injoignable", after.refreshError)
    }

    @Test
    fun `a later successful refresh clears the previous refresh error`() = runTest {
        coEvery { getPositionsUseCase(any(), any()) } returnsMany listOf(
            Result.success(fakePositions),
            Result.failure(RuntimeException("boom")),
            Result.success(fakePositions),
        )
        viewModel = createViewModel()
        viewModel.refresh()
        assertEquals("boom", (viewModel.uiState.value as PositionsUiState.Success).refreshError)

        viewModel.refresh()

        assertEquals(null, (viewModel.uiState.value as PositionsUiState.Success).refreshError)
    }

    @Test
    fun `changing the filter shows the skeleton rather than the previous filter's list`() = runTest {
        val gate = CompletableDeferred<Unit>()
        givenFirstLoadThenGated(gate, Result.success(listOf(fakeClosedTsla)))
        viewModel = createViewModel()
        assertTrue(viewModel.uiState.value is PositionsUiState.Success)

        viewModel.selectFilter(StatusFilter.CLOSED)

        assertTrue(
            "The OPEN list must not be shown under the CLOSED filter, got ${viewModel.uiState.value}",
            viewModel.uiState.value is PositionsUiState.Loading,
        )
        gate.complete(Unit)
        assertEquals(
            listOf(fakeClosedTsla),
            (viewModel.uiState.value as PositionsUiState.Success).positions,
        )
    }

    // ── Portefeuille actif ────────────────────────────────────────────────────

    @Test
    fun `loads the positions of the active portfolio`() = runTest {
        activePortfolio.value = "7"
        viewModel = createViewModel()

        coVerify { getPositionsUseCase("7", PositionStatus.OPEN) }
    }

    @Test
    fun `does not load until an active portfolio is known then loads with the current filter`() = runTest {
        val raw = MutableStateFlow<String?>(null)
        every { observeActivePortfolioUseCase() } returns raw.filterNotNull()
        viewModel = createViewModel()

        viewModel.selectFilter(StatusFilter.CLOSED)
        viewModel.refresh()

        assertTrue(viewModel.uiState.value is PositionsUiState.Loading)
        coVerify(exactly = 0) { getPositionsUseCase(any(), any()) }

        raw.value = "p3"

        coVerify(exactly = 1) { getPositionsUseCase("p3", PositionStatus.CLOSED) }
        assertTrue(viewModel.uiState.value is PositionsUiState.Success)
    }

    @Test
    fun `changing the active portfolio resets to Loading then reloads with the new id`() = runTest {
        val p2Position = fakeOpenTsla.copy(id = 7, symbol = "AAPL")
        val gate = CompletableDeferred<Unit>()
        coEvery { getPositionsUseCase("p1", any()) } returns Result.success(fakePositions)
        coEvery { getPositionsUseCase("p2", any()) } coAnswers {
            gate.await()
            Result.success(listOf(p2Position))
        }
        activePortfolio.value = "p1"
        viewModel = createViewModel()
        assertEquals(fakePositions, (viewModel.uiState.value as PositionsUiState.Success).positions)

        activePortfolio.value = "p2"

        // Les positions de p1 ne doivent plus être visibles sous p2 pendant le rechargement.
        assertTrue(
            "Expected Loading while p2 loads, got ${viewModel.uiState.value}",
            viewModel.uiState.value is PositionsUiState.Loading,
        )
        gate.complete(Unit)

        assertEquals(
            listOf(p2Position),
            (viewModel.uiState.value as PositionsUiState.Success).positions,
        )
        coVerify(exactly = 1) { getPositionsUseCase("p2", PositionStatus.OPEN) }
    }

    @Test
    fun `a stale response for the previous portfolio never reaches the screen`() = runTest {
        val p2Position = fakeOpenTsla.copy(id = 7, symbol = "AAPL")
        val gateP1 = CompletableDeferred<Result<List<Position>>>()
        coEvery { getPositionsUseCase("p1", any()) } coAnswers { gateP1.await() }
        coEvery { getPositionsUseCase("p2", any()) } returns Result.success(listOf(p2Position))
        activePortfolio.value = "p1"
        viewModel = createViewModel() // le chargement de p1 est suspendu

        activePortfolio.value = "p2"
        gateP1.complete(Result.success(fakePositions)) // réponse tardive de p1

        assertEquals(
            listOf(p2Position),
            (viewModel.uiState.value as PositionsUiState.Success).positions,
        )
    }

    @Test
    fun `filter and refresh keep working with the new active portfolio`() = runTest {
        viewModel = createViewModel()
        viewModel.selectFilter(StatusFilter.CLOSED)

        activePortfolio.value = "p2"

        // Le filtre choisi est conservé au changement de portefeuille.
        assertEquals(StatusFilter.CLOSED, viewModel.selectedFilter.value)
        coVerify(exactly = 1) { getPositionsUseCase("p2", PositionStatus.CLOSED) }

        viewModel.refresh()
        viewModel.selectFilter(StatusFilter.ALL)

        coVerify(exactly = 2) { getPositionsUseCase("p2", PositionStatus.CLOSED) }
        coVerify(exactly = 1) { getPositionsUseCase("p2", PositionStatus.ALL) }
        coVerify(exactly = 0) { getPositionsUseCase("1", PositionStatus.ALL) }
    }

    // ── Turbine StateFlow test ────────────────────────────────────────────────

    @Test
    fun `uiState transitions from Loading to Success`() = runTest {
        viewModel = createViewModel()

        viewModel.uiState.test {
            val items = mutableListOf(awaitItem())
            while (items.last() is PositionsUiState.Loading) {
                items.add(awaitItem())
            }
            val finalState = items.last()
            assertTrue(
                "Expected final state to be Success, got $finalState",
                finalState is PositionsUiState.Success,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── WS position_update merge (finding #23 / NEW-ws-position-price) ─────────

    @Test
    fun `position update with last_price updates currentPrice and unrealizedPnl`() = runTest {
        val wsUpdates = MutableSharedFlow<WsUpdate.PositionUpdate>(extraBufferCapacity = 1)
        every { getPositionWsUpdatesUseCase() } returns wsUpdates
        viewModel = createViewModel()

        wsUpdates.emit(
            WsUpdate.PositionUpdate(
                symbol = "TSLA",
                lastPrice = 295.5,
                unrealizedPnl = 455.0,
                isActive = true,
            ),
        )

        val state = viewModel.uiState.value as PositionsUiState.Success
        val updated = state.positions.first()
        assertEquals(0, BigDecimal("295.5").compareTo(updated.currentPrice))
        assertEquals(0, BigDecimal("455.0").compareTo(updated.unrealizedPnl))
    }

    @Test
    fun `position update from another portfolio is ignored, same or unknown portfolio is merged`() = runTest {
        val wsUpdates = MutableSharedFlow<WsUpdate.PositionUpdate>(extraBufferCapacity = 3)
        every { getPositionWsUpdatesUseCase() } returns wsUpdates
        viewModel = createViewModel() // portefeuille actif = "1"

        wsUpdates.emit(WsUpdate.PositionUpdate(symbol = "TSLA", lastPrice = 111.0, portfolioId = "2"))
        val untouched = (viewModel.uiState.value as PositionsUiState.Success).positions.first()
        assertEquals(false, BigDecimal("111.0").compareTo(untouched.currentPrice) == 0)

        wsUpdates.emit(WsUpdate.PositionUpdate(symbol = "TSLA", lastPrice = 222.0, portfolioId = "1"))
        assertEquals(
            0,
            BigDecimal("222.0").compareTo(
                (viewModel.uiState.value as PositionsUiState.Success).positions.first().currentPrice,
            ),
        )

        wsUpdates.emit(WsUpdate.PositionUpdate(symbol = "TSLA", lastPrice = 333.0, portfolioId = null))
        assertEquals(
            0,
            BigDecimal("333.0").compareTo(
                (viewModel.uiState.value as PositionsUiState.Success).positions.first().currentPrice,
            ),
        )
    }

    @Test
    fun `position update for a shared symbol under ALL filter only touches the OPEN row`() = runTest {
        val wsUpdates = MutableSharedFlow<WsUpdate.PositionUpdate>(extraBufferCapacity = 1)
        every { getPositionWsUpdatesUseCase() } returns wsUpdates
        coEvery { getPositionsUseCase(any(), PositionStatus.ALL) } returns
            Result.success(listOf(fakeOpenTsla, fakeClosedTsla))

        viewModel = createViewModel()
        viewModel.selectFilter(StatusFilter.ALL)

        wsUpdates.emit(
            WsUpdate.PositionUpdate(
                symbol = "TSLA",
                lastPrice = 320.0,
                unrealizedPnl = 700.0,
                isActive = true,
            ),
        )

        val state = viewModel.uiState.value as PositionsUiState.Success
        val open = state.positions.first { it.id == 42 }
        val closed = state.positions.first { it.id == 99 }
        assertEquals(0, BigDecimal("320.0").compareTo(open.currentPrice))
        // The CLOSED row for the same symbol must not be touched by a symbol-only match.
        assertEquals(0, BigDecimal("210.00").compareTo(closed.currentPrice))
        assertEquals(0, BigDecimal("50.00").compareTo(closed.unrealizedPnl))
    }

    @Test
    fun `is_active false removes the position under the OPEN filter`() = runTest {
        val wsUpdates = MutableSharedFlow<WsUpdate.PositionUpdate>(extraBufferCapacity = 1)
        every { getPositionWsUpdatesUseCase() } returns wsUpdates
        viewModel = createViewModel()

        wsUpdates.emit(WsUpdate.PositionUpdate(symbol = "TSLA", isActive = false))

        val state = viewModel.uiState.value as PositionsUiState.Success
        assertTrue("Expected the closed position to be dropped", state.positions.isEmpty())
    }

    @Test
    fun `is_active false under ALL filter marks the position CLOSED instead of removing it`() = runTest {
        val wsUpdates = MutableSharedFlow<WsUpdate.PositionUpdate>(extraBufferCapacity = 1)
        every { getPositionWsUpdatesUseCase() } returns wsUpdates
        coEvery { getPositionsUseCase(any(), PositionStatus.ALL) } returns
            Result.success(listOf(fakeOpenTsla))

        viewModel = createViewModel()
        viewModel.selectFilter(StatusFilter.ALL)

        wsUpdates.emit(WsUpdate.PositionUpdate(symbol = "TSLA", isActive = false))

        val state = viewModel.uiState.value as PositionsUiState.Success
        assertEquals(1, state.positions.size)
        assertEquals(PositionStatus.CLOSED, state.positions.first().status)
    }

    // ── Stale filter result dropped (finding C-pos-conc-2) ──────────────────────

    /**
     * [PositionsViewModel.selectFilter]/[PositionsViewModel.refresh] cancel the previous
     * load job, which normally prevents an out-of-order response. This test targets the
     * defense-in-depth guard in [PositionsViewModel.loadPositions] directly — dropping a
     * response whose requested filter no longer matches [PositionsViewModel.selectedFilter]
     * — without depending on `Job` cancellation timing, by driving `loadPositions` for two
     * "filters" concurrently and completing them out of order.
     */
    @Test
    fun `stale load result for a previous filter is dropped once selection has moved on`() = runTest {
        val deferredOpen = CompletableDeferred<Result<List<Position>>>()
        coEvery { getPositionsUseCase(any(), PositionStatus.OPEN) } coAnswers { deferredOpen.await() }
        coEvery { getPositionsUseCase(any(), PositionStatus.ALL) } returns
            Result.success(listOf(fakeOpenTsla))

        viewModel = createViewModel()
        // init's load for OPEN (the default selected filter) is now suspended on deferredOpen.

        // Simulate the selected filter moving to ALL while the OPEN request is still in
        // flight — driven directly via the internal `loadPositions` (not `selectFilter`,
        // which would cancel the OPEN job and make this race unreachable).
        viewModel.forceSelectedFilterForRaceTest(StatusFilter.ALL)
        viewModel.loadPositions(StatusFilter.ALL)

        val afterAll = viewModel.uiState.value as PositionsUiState.Success
        assertEquals(listOf(fakeOpenTsla), afterAll.positions)

        // The stale OPEN response finally arrives — it must not clobber the ALL state.
        deferredOpen.complete(Result.success(fakePositions))

        val finalState = viewModel.uiState.value as PositionsUiState.Success
        assertEquals(
            "Stale OPEN response must be dropped, ALL state must survive",
            listOf(fakeOpenTsla),
            finalState.positions,
        )
    }
}

// ── Helper ────────────────────────────────────────────────────────────────────

private fun assertNotNull(message: String, obj: Any?) {
    org.junit.Assert.assertNotNull(message, obj)
}
