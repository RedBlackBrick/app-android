package com.tradingplatform.app.widget

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.tradingplatform.app.data.api.PortfolioApi
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.local.db.AppDatabase
import com.tradingplatform.app.data.local.db.dao.AlertDao
import com.tradingplatform.app.data.local.db.dao.QuoteDao
import com.tradingplatform.app.data.local.db.dao.WatchlistDao
import com.tradingplatform.app.data.model.BatchPnlItemDto
import com.tradingplatform.app.data.model.BatchPnlResponseDto
import com.tradingplatform.app.data.model.PositionDto
import com.tradingplatform.app.data.repository.PortfolioRepositoryImpl
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.repository.PortfolioSelectionRepository
import com.tradingplatform.app.domain.usecase.market.GetQuoteUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPnlUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionsUseCase
import com.tradingplatform.app.vpn.SystemVpnMonitor
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.math.BigDecimal
import java.time.Instant

/**
 * [WidgetUpdateWorker] test against a **real, in-memory Room database** and a **real
 * [PortfolioRepositoryImpl] / [GetPnlUseCase] / [GetPositionsUseCase] / [WatchlistDao]** chain —
 * only [PortfolioApi] and the non-portfolio dependencies (quotes, alerts, DataStore, VPN) are
 * mocked.
 *
 * Complements [WidgetUpdateWorkerTest] (fully mockk-based use cases): this file verifies that
 * a real `doWork()` run actually leaves the expected rows in `pnl_snapshots` / `positions`, i.e.
 * exercises `PortfolioRepositoryImpl` end to end (mapper + DAO upsert/purge), not just that the
 * use cases are invoked with the right arguments. It also wires a real [WatchlistDao] (post
 * `9ab9d85`, the Worker takes one directly — `resolveQuoteSymbols()` unions
 * `QuoteWidget.configuredSymbols()` ∪ `watchlistDao.getAllSymbols()` ∪ `quoteDao.getAllSymbols()`)
 * so a symbol actually persisted in `watchlist` drives a real quote sync, not a stubbed list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WidgetUpdateWorkerRoomTest {

    private lateinit var db: AppDatabase
    private lateinit var getPositionsUseCase: GetPositionsUseCase
    private lateinit var getPnlUseCase: GetPnlUseCase
    private lateinit var watchlistDao: WatchlistDao

    private val portfolioApi = mockk<PortfolioApi>()
    private val vpnManager = mockk<WireGuardManager>()
    private val systemVpnMonitor = mockk<SystemVpnMonitor>(relaxed = true).apply {
        every { active } returns MutableStateFlow(false)
    }
    private val dataStore = mockk<EncryptedDataStore>()
    private val getQuoteUseCase = mockk<GetQuoteUseCase>()
    private val alertDao = mockk<AlertDao>(relaxed = true)
    private val quoteDao = mockk<QuoteDao>(relaxed = true)

    private val fakeQuote = Quote(
        symbol = "AAPL",
        price = BigDecimal("175.50"),
        bid = BigDecimal("175.48"),
        ask = BigDecimal("175.52"),
        volume = 35_000_000L,
        change = BigDecimal("2.30"),
        changePercent = 1.33,
        timestamp = Instant.now(),
        source = "yahoo",
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        val repository = PortfolioRepositoryImpl(
            portfolioApi = portfolioApi,
            positionDao = db.positionDao(),
            pnlDao = db.pnlDao(),
            portfolioSelection = mockk<PortfolioSelectionRepository>(relaxed = true),
        )
        getPositionsUseCase = GetPositionsUseCase(repository)
        getPnlUseCase = GetPnlUseCase(repository)
        watchlistDao = db.watchlistDao()

        coEvery { dataStore.readString(DataStoreKeys.PORTFOLIO_ID) } returns "1"
        coEvery { portfolioApi.getPositions("1", "open") } returns Response.success(
            listOf(
                PositionDto(
                    id = 1,
                    symbol = "AAPL",
                    quantity = BigDecimal("10"),
                    avgPrice = BigDecimal("150.00"),
                    currentPrice = BigDecimal("175.00"),
                    unrealizedPnl = BigDecimal("250.00"),
                    unrealizedPnlPercent = 16.67,
                    isActive = true,
                    openedAt = null,
                )
            )
        )
        // Période DAY : `batch/pnl` (pnl_pct = FRACTION 0.045 = 4,5 %).
        coEvery { portfolioApi.getBatchPnl(any()) } returns Response.success(
            BatchPnlResponseDto(
                data = mapOf(
                    "1" to BatchPnlItemDto(
                        portfolioId = "1",
                        pnlAmount = "150.00",
                        pnlPct = 0.045,
                        previousValue = "3333.33",
                        currentValue = "3483.33",
                        currencyCode = "EUR",
                    ),
                ),
            )
        )
        coEvery { quoteDao.getAllSymbols() } returns listOf("AAPL")
        coEvery { getQuoteUseCase(any()) } returns Result.success(fakeQuote)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun buildWorker(): WidgetUpdateWorker =
        TestListenableWorkerBuilder<WidgetUpdateWorker>(
            ApplicationProvider.getApplicationContext()
        ).setWorkerFactory(
            object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: androidx.work.WorkerParameters,
                ): ListenableWorker = WidgetUpdateWorker(
                    context = appContext,
                    workerParams = workerParameters,
                    vpnManager = vpnManager,
                    systemVpnMonitor = systemVpnMonitor,
                    dataStore = dataStore,
                    getPositionsUseCase = getPositionsUseCase,
                    getPnlUseCase = getPnlUseCase,
                    getQuoteUseCase = getQuoteUseCase,
                    alertDao = alertDao,
                    quoteDao = quoteDao,
                    watchlistDao = watchlistDao,
                )
            }
        ).build()

    @Test
    fun `doWork with VPN connected leaves a pnl_snapshots DAY row and a positions row in Room`() = runTest {
        every { vpnManager.state } returns MutableStateFlow(VpnState.Connected())

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)

        val pnlRow = db.pnlDao().getByPeriod("day")
        assertNotNull("Expected a real pnl_snapshots row written by PortfolioRepositoryImpl", pnlRow)
        assertEquals(0.045, pnlRow!!.totalPnlPercent, 1e-9)

        val positions = db.positionDao().getAll()
        assertEquals(1, positions.size)
        assertEquals("AAPL", positions.first().symbol)
    }

    @Test
    fun `doWork with VPN disconnected returns success without writing Room or calling the API`() = runTest {
        every { vpnManager.state } returns MutableStateFlow(VpnState.Disconnected)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNull("No pnl_snapshots row should be written when VPN is down", db.pnlDao().getByPeriod("day"))
        assertEquals(
            "No positions row should be written when VPN is down",
            0,
            db.positionDao().getAll().size,
        )
        coVerify(exactly = 0) { portfolioApi.getPositions(any(), any()) }
        coVerify(exactly = 0) { portfolioApi.getBatchPnl(any()) }
        coVerify(exactly = 0) { portfolioApi.getPnl(any(), any()) }
        coVerify(exactly = 0) { getQuoteUseCase(any()) }
    }

    @Test
    fun `doWork syncs quotes for symbols persisted in the real watchlist DAO`() = runTest {
        every { vpnManager.state } returns MutableStateFlow(VpnState.Connected())
        // quotes cache is empty in this scenario — only the watchlist has a symbol.
        coEvery { quoteDao.getAllSymbols() } returns emptyList()
        watchlistDao.insert(com.tradingplatform.app.data.local.db.entity.WatchlistEntity(symbol = "TSLA"))
        coEvery { getQuoteUseCase("TSLA") } returns Result.success(fakeQuote.copy(symbol = "TSLA"))

        buildWorker().doWork()

        coVerify(exactly = 1) { getQuoteUseCase("TSLA") }
    }
}
