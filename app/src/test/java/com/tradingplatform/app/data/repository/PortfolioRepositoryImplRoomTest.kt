package com.tradingplatform.app.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tradingplatform.app.data.api.PortfolioApi
import com.tradingplatform.app.data.local.db.AppDatabase
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.data.local.db.dao.PnlDao
import com.tradingplatform.app.data.local.db.dao.PositionDao
import com.tradingplatform.app.data.local.db.entity.PositionEntity
import com.tradingplatform.app.data.model.BatchPnlItemDto
import com.tradingplatform.app.data.model.BatchPnlResponseDto
import com.tradingplatform.app.data.model.PositionDto
import com.tradingplatform.app.data.model.PnlResponseDto
import com.tradingplatform.app.data.model.toEntity
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.domain.repository.PortfolioSelectionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal

/**
 * Integration tests for [PortfolioRepositoryImpl] against a **real, in-memory Room database**
 * (Robolectric-backed JVM SQLite) — only [PortfolioApi] is mocked. Verifies the actual cache
 * read/write/purge behavior described in CLAUDE.md §2 ("Stratégie de cache Room" /
 * "Politique de rétention Room"), as opposed to [PortfolioRepositoryImplTest] (pure mockk on
 * the DAOs) which only verifies *that* the DAO methods are called.
 *
 * Read against the CURRENT source (post-`9ab9d85`, `audit/remediation`), which reshaped
 * `getPosition`:
 * - `getPosition(portfolioId, positionId, forceRefresh = false)` now returns
 *   `Result<Cached<Position>>` ([com.tradingplatform.app.domain.model.Cached]). The Room cache
 *   is served only when `cached != null && !forceRefresh && CacheTtl.isFresh(cached.syncedAt, CacheTtl.POSITIONS_MS)`.
 *   On a miss, a stale row, or `forceRefresh = true`, it re-fetches with `status=all` (not
 *   "open") so closed positions resolve too, then upserts the batch into Room.
 * - If that fetch throws (network/HTTP error) and a cached row exists (however stale), it is
 *   returned as a graceful fallback with its **real** `syncedAt` — no bubbled-up failure. Only
 *   a true miss (no cached row at all) propagates the failure. This fallback does NOT cover the
 *   case where the fetch *succeeds* but the batch simply doesn't contain the requested id —
 *   that still fails outright (see test below).
 * - `getPnlSummary` remains the sole writer of `pnl_snapshots`, one row per period (`period` is
 *   the Room primary key), via `PnlDao.upsertAndPurge` — but the purge cutoff is now
 *   [CacheTtl.PNL_RETENTION_MS] (24 h), not the 5 min freshness TTL, precisely so that syncing
 *   one period doesn't purge another period's row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PortfolioRepositoryImplRoomTest {

    private lateinit var db: AppDatabase
    private lateinit var positionDao: PositionDao
    private lateinit var pnlDao: PnlDao
    private val portfolioApi = mockk<PortfolioApi>()

    private lateinit var repository: PortfolioRepositoryImpl

    private val portfolioId = "42"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        positionDao = db.positionDao()
        pnlDao = db.pnlDao()

        repository = PortfolioRepositoryImpl(
            portfolioApi = portfolioApi,
            positionDao = positionDao,
            pnlDao = pnlDao,
            portfolioSelection = mockk<PortfolioSelectionRepository>(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── getPnlSummary — writes pnl_snapshots ──────────────────────────────────

    /** `/pnl` (ALL / YEAR) : `total_pnl_percent` est un POURCENTAGE. */
    private fun pnlDto(period: String, totalPnlPercent: Double = 4.5) = PnlResponseDto(
        period = period,
        realizedPnl = BigDecimal("100.00"),
        unrealizedPnl = BigDecimal("50.00"),
        totalPnl = BigDecimal("150.00"),
        totalPnlPercent = totalPnlPercent,
        tradesCount = 10,
        winningTrades = 7,
        losingTrades = 3,
    )

    /** `batch/pnl` (DAY / WEEK / MONTH) : `pnl_pct` est une FRACTION. */
    private fun batchPnl(amount: String = "150.00", fraction: Double = 0.045) = Response.success(
        BatchPnlResponseDto(
            data = mapOf(
                portfolioId to BatchPnlItemDto(
                    portfolioId = portfolioId,
                    pnlAmount = amount,
                    pnlPct = fraction,
                    previousValue = "3333.33",
                    currentValue = "3483.33",
                    currencyCode = "EUR",
                ),
            ),
        ),
    )

    @Test
    fun `getPnlSummary DAY persists a row from batch pnl with the fraction stored as is`() = runTest {
        coEvery { portfolioApi.getBatchPnl(any()) } returns batchPnl(amount = "150.00", fraction = 0.045)

        val result = repository.getPnlSummary(portfolioId, PnlPeriod.DAY)

        assertTrue(result.isSuccess)
        val row = pnlDao.getByPeriod("day")
        assertNotNull("Expected a pnl_snapshots row for period=day", row)
        assertEquals("day", row!!.period)
        assertEquals("150.00", row.totalPnl)
        // batch/pnl envoie DÉJÀ une fraction (0.045) : le cache la stocke telle quelle, sans /100.
        assertEquals(0.045, row.totalPnlPercent, 1e-9)
        // batch/pnl ne fournit ni compteurs de trades ni réalisé/latent : écrits à zéro.
        assertEquals(0, row.tradesCount)
        assertEquals("0", row.realizedPnl)
    }

    @Test
    fun `getPnlSummary ALL persists a row from pnl with the percent stored as a fraction`() = runTest {
        coEvery { portfolioApi.getPnl(portfolioId, "all") } returns Response.success(pnlDto("all", totalPnlPercent = 4.5))

        val result = repository.getPnlSummary(portfolioId, PnlPeriod.ALL)

        assertTrue(result.isSuccess)
        val row = pnlDao.getByPeriod("all")
        assertNotNull("Expected a pnl_snapshots row for period=all", row)
        // Backend sends a percent (4.5) — the Room cache stores a fraction (mapper divides by 100).
        assertEquals(0.045, row!!.totalPnlPercent, 1e-9)
        assertEquals(10, row.tradesCount)
        assertEquals(7, row.winningTrades)
        assertEquals(3, row.losingTrades)
    }

    @Test
    fun `getPnlSummary for a second period adds a row without deleting the first (24h retention, not 5 min freshness)`() = runTest {
        coEvery { portfolioApi.getBatchPnl(match { it.period == "day" }) } returns batchPnl(fraction = 0.045)
        coEvery { portfolioApi.getBatchPnl(match { it.period == "week" }) } returns batchPnl(fraction = 0.09)

        repository.getPnlSummary(portfolioId, PnlPeriod.DAY)
        repository.getPnlSummary(portfolioId, PnlPeriod.WEEK)

        val dayRow = pnlDao.getByPeriod("day")
        val weekRow = pnlDao.getByPeriod("week")
        assertNotNull(
            "DAY row must survive a subsequent WEEK sync — purge cutoff is CacheTtl.PNL_RETENTION_MS " +
                "(24h), not the 5 min freshness TTL, precisely to avoid this",
            dayRow,
        )
        assertNotNull(weekRow)
        assertEquals(0.09, weekRow!!.totalPnlPercent, 1e-9)
    }

    @Test
    fun `getPnlSummary purges a period row older than CacheTtl PNL_RETENTION_MS (24h)`() = runTest {
        val now = System.currentTimeMillis()
        // Seed a MONTH row abandoned by its widget, synced just over 24h ago.
        pnlDao.upsert(
            pnlDto("month", totalPnlPercent = 1.0).toEntity(
                period = PnlPeriod.MONTH,
                syncedAt = now - (CacheTtl.PNL_RETENTION_MS + 60_000L),
            )
        )
        // And a YEAR row still within the 24h retention window.
        pnlDao.upsert(
            pnlDto("ytd", totalPnlPercent = 2.0).toEntity(
                period = PnlPeriod.YEAR,
                syncedAt = now - (CacheTtl.PNL_RETENTION_MS - 60_000L),
            )
        )

        coEvery { portfolioApi.getBatchPnl(any()) } returns batchPnl()
        repository.getPnlSummary(portfolioId, PnlPeriod.DAY)

        assertNull("MONTH row is older than the 24h retention cutoff — must be purged", pnlDao.getByPeriod("month"))
        assertNotNull("YTD row is within the 24h retention window — must survive", pnlDao.getByPeriod("ytd"))
        assertNotNull(pnlDao.getByPeriod("day"))
    }

    @Test
    fun `getPnlSummary upserts (REPLACE) rather than duplicating a row for the same period`() = runTest {
        coEvery { portfolioApi.getBatchPnl(any()) } returns batchPnl(fraction = 0.045)
        repository.getPnlSummary(portfolioId, PnlPeriod.DAY)

        coEvery { portfolioApi.getBatchPnl(any()) } returns batchPnl(fraction = 0.06)
        repository.getPnlSummary(portfolioId, PnlPeriod.DAY)

        val row = pnlDao.getByPeriod("day")
        assertNotNull(row)
        assertEquals(0.06, row!!.totalPnlPercent, 1e-9)
    }

    @Test
    fun `getPnlSummary returns failure and does not write Room when the API call errors`() = runTest {
        coEvery { portfolioApi.getBatchPnl(any()) } returns
            Response.error(500, "boom".toResponseBody(null))

        val result = repository.getPnlSummary(portfolioId, PnlPeriod.DAY)

        assertTrue(result.isFailure)
        assertNull(pnlDao.getByPeriod("day"))
    }

    // ── getPositions — upsert + purge ─────────────────────────────────────────

    private fun positionDto(id: Int, symbol: String) = PositionDto(
        id = id,
        symbol = symbol,
        quantity = BigDecimal("10"),
        avgPrice = BigDecimal("100.00"),
        currentPrice = BigDecimal("110.00"),
        unrealizedPnl = BigDecimal("100.00"),
        unrealizedPnlPercent = 10.0,
        isActive = true,
        openedAt = null,
    )

    private fun staleEntity(id: Int, symbol: String, syncedAt: Long) = PositionEntity(
        id = id,
        symbol = symbol,
        quantity = "1",
        avgPrice = "1.00",
        currentPrice = null,
        unrealizedPnl = null,
        unrealizedPnlPercent = null,
        status = "open",
        openedAt = null,
        syncedAt = syncedAt,
    )

    @Test
    fun `getPositions upserts fetched positions into Room`() = runTest {
        coEvery { portfolioApi.getPositions(portfolioId, "open") } returns
            Response.success(listOf(positionDto(1, "AAPL"), positionDto(2, "TSLA")))

        val result = repository.getPositions(portfolioId, PositionStatus.OPEN)

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().size)
        val cached = positionDao.getAll()
        assertEquals(2, cached.size)
        assertEquals(setOf("AAPL", "TSLA"), cached.map { it.symbol }.toSet())
    }

    @Test
    fun `getPositions purges rows older than CacheTtl POSITIONS_MS after a successful sync`() = runTest {
        val now = System.currentTimeMillis()
        // Manually seed a stale row (past CacheTtl.POSITIONS_MS) that the upcoming sync
        // response does not include.
        positionDao.upsertAll(listOf(staleEntity(99, "OLD", syncedAt = now - (CacheTtl.POSITIONS_MS + 60_000L))))

        coEvery { portfolioApi.getPositions(portfolioId, "open") } returns
            Response.success(listOf(positionDto(1, "AAPL")))

        repository.getPositions(portfolioId, PositionStatus.OPEN)

        val cached = positionDao.getAll()
        assertEquals("Stale OLD row must be purged, fresh AAPL row kept", listOf("AAPL"), cached.map { it.symbol })
    }

    // ── getPosition — Cached<Position>, fresh-cache-first with forceRefresh + fallback ──

    @Test
    fun `getPosition serves the fresh cache without calling the API`() = runTest {
        val syncedAt = System.currentTimeMillis() - 1_000L // 1s old — well within CacheTtl.POSITIONS_MS (5 min)
        positionDao.upsertAll(listOf(staleEntity(7, "MSFT", syncedAt = syncedAt).copy(currentPrice = "320.00")))

        val result = repository.getPosition(portfolioId, 7)

        assertTrue(result.isSuccess)
        val cachedResult = result.getOrThrow()
        assertEquals("MSFT", cachedResult.value.symbol)
        assertEquals(syncedAt, cachedResult.syncedAt)
        coVerify(exactly = 0) { portfolioApi.getPositions(any(), any()) }
    }

    @Test
    fun `getPosition re-fetches with status=all when the cached row is older than CacheTtl POSITIONS_MS`() = runTest {
        val staleSyncedAt = System.currentTimeMillis() - (CacheTtl.POSITIONS_MS + 60_000L)
        positionDao.upsertAll(listOf(staleEntity(7, "MSFT", syncedAt = staleSyncedAt)))

        coEvery { portfolioApi.getPositions(portfolioId, "all") } returns
            Response.success(listOf(positionDto(7, "MSFT")))

        val result = repository.getPosition(portfolioId, 7)

        assertTrue(result.isSuccess)
        assertEquals("MSFT", result.getOrThrow().value.symbol)
        coVerify(exactly = 1) { portfolioApi.getPositions(portfolioId, "all") }
        coVerify(exactly = 0) { portfolioApi.getPositions(portfolioId, "open") }
    }

    @Test
    fun `getPosition fetches with status=all on a cache miss, then upserts the result`() = runTest {
        coEvery { portfolioApi.getPositions(portfolioId, "all") } returns
            Response.success(listOf(positionDto(1, "AAPL"), positionDto(2, "TSLA")))

        val result = repository.getPosition(portfolioId, 2)

        assertTrue(result.isSuccess)
        assertEquals("TSLA", result.getOrThrow().value.symbol)
        coVerify(exactly = 1) { portfolioApi.getPositions(portfolioId, "all") }
        // The fetched batch is upserted into Room as a side effect, even though only one
        // position was requested.
        assertEquals(2, positionDao.getAll().size)
    }

    @Test
    fun `getPosition bypasses a fresh cache and re-fetches with status=all when forceRefresh=true`() = runTest {
        val syncedAt = System.currentTimeMillis() - 1_000L // fresh
        positionDao.upsertAll(listOf(staleEntity(7, "MSFT", syncedAt = syncedAt)))

        coEvery { portfolioApi.getPositions(portfolioId, "all") } returns
            Response.success(listOf(positionDto(7, "MSFT")))

        val result = repository.getPosition(portfolioId, 7, forceRefresh = true)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { portfolioApi.getPositions(portfolioId, "all") }
    }

    @Test
    fun `getPosition falls back to the stale cached row (with its real syncedAt) when the network call throws`() = runTest {
        val staleSyncedAt = System.currentTimeMillis() - (CacheTtl.POSITIONS_MS + 60_000L)
        positionDao.upsertAll(listOf(staleEntity(7, "MSFT", syncedAt = staleSyncedAt)))

        coEvery { portfolioApi.getPositions(portfolioId, "all") } throws IOException("no network")

        val result = repository.getPosition(portfolioId, 7)

        assertTrue(
            "A stale cached row must be served as a graceful fallback rather than failing outright",
            result.isSuccess,
        )
        val cachedResult = result.getOrThrow()
        assertEquals("MSFT", cachedResult.value.symbol)
        assertEquals("Fallback must report the row's REAL (stale) syncedAt, not now()", staleSyncedAt, cachedResult.syncedAt)
    }

    @Test
    fun `getPosition falls back to the stale cached row when the API responds with an HTTP error`() = runTest {
        val staleSyncedAt = System.currentTimeMillis() - (CacheTtl.POSITIONS_MS + 60_000L)
        positionDao.upsertAll(listOf(staleEntity(7, "MSFT", syncedAt = staleSyncedAt)))

        coEvery { portfolioApi.getPositions(portfolioId, "all") } returns
            Response.error(500, "boom".toResponseBody(null))

        val result = repository.getPosition(portfolioId, 7)

        assertTrue(result.isSuccess)
        assertEquals("MSFT", result.getOrThrow().value.symbol)
    }

    @Test
    fun `getPosition returns failure when the network call throws and there is no cached row at all`() = runTest {
        coEvery { portfolioApi.getPositions(portfolioId, "all") } throws IOException("no network")

        val result = repository.getPosition(portfolioId, 7)

        assertTrue(result.isFailure)
    }

    @Test
    fun `getPosition returns failure when the fetch succeeds but omits the requested id, even with a stale cached row for that same id`() = runTest {
        // Distinct from the network/HTTP-error fallback tests above: here the fetch itself
        // succeeds (200 OK), it just doesn't contain positionId=999 in the response body
        // (e.g. deleted server-side). `error("Position $id not found")` is thrown OUTSIDE the
        // try/catch that falls back to a stale cache, so this always fails as Result.failure —
        // even though a stale cached row for that exact id triggered the re-fetch in the first
        // place. Only a thrown exception (network/HTTP error) falls back to the stale row.
        val staleSyncedAt = System.currentTimeMillis() - (CacheTtl.POSITIONS_MS + 60_000L)
        positionDao.upsertAll(listOf(staleEntity(999, "DELISTED", syncedAt = staleSyncedAt)))

        coEvery { portfolioApi.getPositions(portfolioId, "all") } returns
            Response.success(listOf(positionDto(1, "AAPL")))

        val result = repository.getPosition(portfolioId, 999)

        assertTrue(result.isFailure)
    }
}
