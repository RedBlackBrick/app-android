package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.PortfolioApi
import com.tradingplatform.app.data.local.db.dao.PnlDao
import com.tradingplatform.app.data.local.db.dao.PositionDao
import com.tradingplatform.app.data.local.db.entity.PnlSnapshotEntity
import com.tradingplatform.app.data.model.BatchPnlItemDto
import com.tradingplatform.app.data.model.BatchPnlRequestDto
import com.tradingplatform.app.data.model.BatchPnlResponseDto
import com.tradingplatform.app.data.model.DashboardOverviewDto
import com.tradingplatform.app.data.model.DashboardPortfolioDto
import com.tradingplatform.app.data.model.PortfolioBrokerConnectionDto
import com.tradingplatform.app.data.model.ValueHistoryPointDto
import com.tradingplatform.app.data.model.ValueHistoryResponseDto
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.model.PortfolioBrokerStatus
import com.tradingplatform.app.domain.repository.PortfolioSelectionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Lectures « vue d'ensemble » de [PortfolioRepositoryImpl] : `getPortfoliosOverview`,
 * `getPnlSummary` DAY/WEEK/MONTH (via `batch/pnl`), `getNavCurve`, `getBrokerStatus`.
 * L'API est mockée (style de [PortfolioRepositoryImplTest]) ; le temps est figé au
 * 2026-09-29T10:00:00Z via le constructeur interne.
 */
class PortfolioRepositoryImplOverviewTest {

    private val portfolioApi = mockk<PortfolioApi>()
    private val positionDao = mockk<PositionDao>(relaxed = true)
    private val pnlDao = mockk<PnlDao>(relaxed = true)
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneOffset.UTC)

    private val known = listOf(
        Portfolio(id = "p1", name = "Growth EUR", currency = "EUR"),
        Portfolio(id = "p2", name = "Paper USD", currency = "USD"),
    )

    private fun selectionWith(
        list: List<Portfolio>,
        refreshed: Result<List<Portfolio>>? = null,
    ): PortfolioSelectionRepository = mockk<PortfolioSelectionRepository>().also { selection ->
        every { selection.portfolios } returns MutableStateFlow(list)
        if (refreshed != null) coEvery { selection.refresh() } returns refreshed
    }

    private fun repo(selection: PortfolioSelectionRepository = selectionWith(known)) =
        PortfolioRepositoryImpl(portfolioApi, positionDao, pnlDao, selection, clock)

    private fun batchItem(
        id: String,
        amount: String,
        pct: Double?,
        previous: String,
        current: String,
        currency: String? = "EUR",
    ) = BatchPnlItemDto(
        portfolioId = id,
        pnlAmount = amount,
        pnlPct = pct,
        previousValue = previous,
        currentValue = current,
        currencyCode = currency,
    )

    private fun batchResponse(vararg items: BatchPnlItemDto): Response<BatchPnlResponseDto> =
        Response.success(BatchPnlResponseDto(items.associateBy { it.portfolioId ?: "?" }))

    // ── getPortfoliosOverview — DAY / WEEK / MONTH via batch/pnl ─────────────────

    @Test
    fun `overview DAY maps the batch with list names and keeps pnl_pct as a fraction`() = runTest {
        coEvery { portfolioApi.getBatchPnl(any()) } returns batchResponse(
            batchItem("p1", amount = "-84.31", pct = -0.00826, previous = "10158.31", current = "10074.00"),
            // previous_value == current_value (pas de snapshot antérieur) : P&L nul.
            batchItem("p2", amount = "0.00", pct = 0.0, previous = "500.00", current = "500.00", currency = "USD"),
        )

        val items = repo().getPortfoliosOverview(PnlPeriod.DAY).getOrThrow()

        assertEquals(2, items.size)
        val first = items[0]
        assertEquals("p1", first.portfolioId)
        assertEquals("Growth EUR", first.name)
        assertEquals("EUR", first.currency)
        assertEquals(BigDecimal("10074.00"), first.currentValue)
        assertEquals(BigDecimal("-84.31"), first.periodPnl)
        // FRACTION du backend, jamais divisée par 100 : -0,826 % = -0.00826 (pas -0.0000826).
        assertEquals(-0.00826, first.periodPnlPct!!, 1e-12)
        val second = items[1]
        assertEquals("Paper USD", second.name)
        assertEquals("USD", second.currency)
        assertEquals(BigDecimal("500.00"), second.currentValue)
        assertEquals(BigDecimal("0.00"), second.periodPnl)
        assertEquals(0.0, second.periodPnlPct!!, 1e-12)
        coVerify(exactly = 1) { portfolioApi.getBatchPnl(BatchPnlRequestDto(listOf("p1", "p2"), "day")) }
        coVerify(exactly = 0) { portfolioApi.getDashboardOverview() }
    }

    @Test
    fun `overview WEEK and MONTH send their own period to batch pnl`() = runTest {
        coEvery { portfolioApi.getBatchPnl(any()) } returns batchResponse(
            batchItem("p1", amount = "10.00", pct = 0.001, previous = "10000.00", current = "10010.00"),
        )

        repo().getPortfoliosOverview(PnlPeriod.WEEK).getOrThrow()
        repo().getPortfoliosOverview(PnlPeriod.MONTH).getOrThrow()

        coVerify(exactly = 1) { portfolioApi.getBatchPnl(BatchPnlRequestDto(listOf("p1", "p2"), "week")) }
        coVerify(exactly = 1) { portfolioApi.getBatchPnl(BatchPnlRequestDto(listOf("p1", "p2"), "month")) }
    }

    @Test
    fun `overview skips a portfolio the backend does not know or does not return`() = runTest {
        coEvery { portfolioApi.getBatchPnl(any()) } returns batchResponse(
            // id inconnu du backend : currency_code null, montants à « 0 » sans signification.
            batchItem("p1", amount = "0", pct = 0.0, previous = "0", current = "0", currency = null),
        )

        val items = repo().getPortfoliosOverview(PnlPeriod.DAY).getOrThrow()

        assertTrue(items.isEmpty())
    }

    @Test
    fun `overview refreshes the portfolio list first when it is empty`() = runTest {
        val selection = selectionWith(emptyList(), refreshed = Result.success(known))
        coEvery { portfolioApi.getBatchPnl(any()) } returns batchResponse(
            batchItem("p1", amount = "5.00", pct = 0.0005, previous = "10000.00", current = "10005.00"),
        )

        val items = repo(selection).getPortfoliosOverview(PnlPeriod.DAY).getOrThrow()

        coVerify(exactly = 1) { selection.refresh() }
        assertEquals(listOf("p1"), items.map { it.portfolioId })
        coVerify(exactly = 1) { portfolioApi.getBatchPnl(BatchPnlRequestDto(listOf("p1", "p2"), "day")) }
    }

    @Test
    fun `overview fails without calling the API when the refresh fails`() = runTest {
        val selection = selectionWith(
            emptyList(),
            refreshed = Result.failure(IllegalStateException("No portfolio found")),
        )

        val result = repo(selection).getPortfoliosOverview(PnlPeriod.DAY)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { portfolioApi.getBatchPnl(any()) }
    }

    @Test
    fun `overview fails on a batch HTTP error`() = runTest {
        coEvery { portfolioApi.getBatchPnl(any()) } returns Response.error(500, "boom".toResponseBody(null))

        assertTrue(repo().getPortfoliosOverview(PnlPeriod.DAY).isFailure)
    }

    // ── getPortfoliosOverview — ALL / YEAR via dashboard/overview ────────────────

    private fun dashboardResponse(vararg portfolios: DashboardPortfolioDto): Response<DashboardOverviewDto> =
        Response.success(DashboardOverviewDto(portfolios.toList()))

    @Test
    fun `overview ALL computes pnl as current minus initial capital, in list order`() = runTest {
        coEvery { portfolioApi.getDashboardOverview() } returns dashboardResponse(
            // Ordre du dashboard (created_at DESC) inversé : l'ordre de la liste des portefeuilles prime.
            DashboardPortfolioDto(id = "p2", currentValue = "480.00", initialCapital = "500.00"),
            DashboardPortfolioDto(id = "p1", currentValue = "10074.00", initialCapital = "10000.00"),
        )

        val items = repo().getPortfoliosOverview(PnlPeriod.ALL).getOrThrow()

        assertEquals(listOf("p1", "p2"), items.map { it.portfolioId })
        assertEquals("Growth EUR", items[0].name)
        assertEquals(BigDecimal("10074.00"), items[0].currentValue)
        assertEquals(BigDecimal("74.00"), items[0].periodPnl)
        assertEquals(0.0074, items[0].periodPnlPct!!, 1e-9)
        assertEquals(BigDecimal("-20.00"), items[1].periodPnl)
        assertEquals(-0.04, items[1].periodPnlPct!!, 1e-9)
        coVerify(exactly = 0) { portfolioApi.getBatchPnl(any()) }
    }

    @Test
    fun `overview YEAR also uses the dashboard overview`() = runTest {
        coEvery { portfolioApi.getDashboardOverview() } returns dashboardResponse(
            DashboardPortfolioDto(id = "p1", currentValue = "10100.00", initialCapital = "10000.00"),
        )

        val items = repo().getPortfoliosOverview(PnlPeriod.YEAR).getOrThrow()

        assertEquals(BigDecimal("100.00"), items.single().periodPnl)
        coVerify(exactly = 1) { portfolioApi.getDashboardOverview() }
        coVerify(exactly = 0) { portfolioApi.getBatchPnl(any()) }
    }

    @Test
    fun `overview ALL has a null percentage when the initial capital is zero or missing`() = runTest {
        coEvery { portfolioApi.getDashboardOverview() } returns dashboardResponse(
            DashboardPortfolioDto(id = "p1", currentValue = "100.00", initialCapital = "0.00"),
            DashboardPortfolioDto(id = "p2", currentValue = "50.00", initialCapital = null),
        )

        val items = repo().getPortfoliosOverview(PnlPeriod.ALL).getOrThrow()

        assertEquals(BigDecimal("100.00"), items[0].periodPnl)
        assertNull(items[0].periodPnlPct)
        assertNull(items[1].periodPnl)
        assertNull(items[1].periodPnlPct)
    }

    // ── getPnlSummary — DAY / WEEK / MONTH via batch/pnl ─────────────────────────

    @Test
    fun `getPnlSummary DAY takes the amount from batch pnl, exposes no winRate and never calls pnl`() = runTest {
        coEvery { portfolioApi.getBatchPnl(any()) } returns batchResponse(
            batchItem("p1", amount = "-84.31", pct = -0.00826, previous = "10158.31", current = "10074.00"),
        )
        val entity = slot<PnlSnapshotEntity>()

        val summary = repo().getPnlSummary("p1", PnlPeriod.DAY).getOrThrow()

        assertEquals(BigDecimal("-84.31"), summary.totalReturn)
        assertEquals(-0.00826, summary.totalReturnPct!!, 1e-12)
        assertNull(summary.winRate)
        coVerify(exactly = 1) { portfolioApi.getBatchPnl(BatchPnlRequestDto(listOf("p1"), "day")) }
        coVerify(exactly = 0) { portfolioApi.getPnl(any(), any()) }
        // La ligne pnl_snapshots de la période reste écrite (PnlWidget) avec les mêmes valeurs.
        coVerify(exactly = 1) { pnlDao.upsertAndPurge(capture(entity), any()) }
        assertEquals("day", entity.captured.period)
        assertEquals("-84.31", entity.captured.totalPnl)
        assertEquals(-0.00826, entity.captured.totalPnlPercent, 1e-12)
    }

    @Test
    fun `getPnlSummary MONTH sends the month period`() = runTest {
        coEvery { portfolioApi.getBatchPnl(any()) } returns batchResponse(
            batchItem("p1", amount = "250.00", pct = 0.025, previous = "10000.00", current = "10250.00"),
        )

        repo().getPnlSummary("p1", PnlPeriod.MONTH).getOrThrow()

        coVerify(exactly = 1) { portfolioApi.getBatchPnl(BatchPnlRequestDto(listOf("p1"), "month")) }
    }

    @Test
    fun `getPnlSummary derives the fraction from the amounts when pnl_pct is missing`() = runTest {
        coEvery { portfolioApi.getBatchPnl(any()) } returns batchResponse(
            batchItem("p1", amount = "10.00", pct = null, previous = "200.00", current = "210.00"),
        )

        val summary = repo().getPnlSummary("p1", PnlPeriod.WEEK).getOrThrow()

        assertEquals(0.05, summary.totalReturnPct!!, 1e-12)
    }

    @Test
    fun `getPnlSummary DAY fails and writes nothing when the portfolio is unknown to the backend`() = runTest {
        coEvery { portfolioApi.getBatchPnl(any()) } returns batchResponse(
            batchItem("p1", amount = "0", pct = 0.0, previous = "0", current = "0", currency = null),
        )

        assertTrue(repo().getPnlSummary("p1", PnlPeriod.DAY).isFailure)
        coVerify(exactly = 0) { pnlDao.upsertAndPurge(any(), any()) }
    }

    @Test
    fun `getPnlSummary DAY fails and writes nothing when the id is absent from the batch`() = runTest {
        coEvery { portfolioApi.getBatchPnl(any()) } returns Response.success(BatchPnlResponseDto())

        assertTrue(repo().getPnlSummary("p1", PnlPeriod.DAY).isFailure)
        coVerify(exactly = 0) { pnlDao.upsertAndPurge(any(), any()) }
    }

    @Test
    fun `getPnlSummary DAY purges with the 24 hour retention cutoff`() = runTest {
        coEvery { portfolioApi.getBatchPnl(any()) } returns batchResponse(
            batchItem("p1", amount = "1.00", pct = 0.0001, previous = "10000.00", current = "10001.00"),
        )
        val cutoff = slot<Long>()
        val before = System.currentTimeMillis()

        repo().getPnlSummary("p1", PnlPeriod.DAY).getOrThrow()

        coVerify { pnlDao.upsertAndPurge(any(), capture(cutoff)) }
        // Rétention 24 h (une ligne par période), pas la fraîcheur de 5 min.
        assertTrue(cutoff.captured <= before - 23 * 60 * 60 * 1000L)
    }

    // ── getNavCurve ──────────────────────────────────────────────────────────────

    private fun point(at: String, value: String?) = ValueHistoryPointDto(totalValue = value, recordedAt = at)

    private fun history(vararg items: ValueHistoryPointDto): Response<ValueHistoryResponseDto> =
        Response.success(ValueHistoryResponseDto(items.toList()))

    @Test
    fun `getNavCurve bounds every request with start_date and granularity`() = runTest {
        coEvery { portfolioApi.getValueHistory(any(), any(), any(), any()) } returns history()
        val expected = listOf(
            Triple(PnlPeriod.DAY, "2026-09-28", "raw"),
            Triple(PnlPeriod.WEEK, "2026-09-22", "daily"),
            Triple(PnlPeriod.MONTH, "2026-08-30", "daily"),
            Triple(PnlPeriod.YEAR, "2025-09-30", "daily"),
            Triple(PnlPeriod.ALL, "2025-09-30", "daily"),
        )

        for ((period, startDate, granularity) in expected) {
            assertTrue(repo().getNavCurve("p1", period).isSuccess)
            coVerify(atLeast = 1) { portfolioApi.getValueHistory("p1", startDate, granularity, null) }
        }
    }

    @Test
    fun `getNavCurve samples a long history down to at most 120 points, keeping both ends`() = runTest {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val items = (0 until 500).map { i ->
            point(start.plusSeconds(i * 3_600L).toString(), "${1_000 + i}.00")
        }
        coEvery { portfolioApi.getValueHistory(any(), any(), any(), any()) } returns history(*items.toTypedArray())

        val curve = repo().getNavCurve("p1", PnlPeriod.MONTH).getOrThrow()

        assertEquals(120, curve.points.size)
        assertEquals(BigDecimal("1000.00"), curve.points.first().value)
        assertEquals(BigDecimal("1499.00"), curve.points.last().value)
        assertTrue(curve.points.zipWithNext().all { (a, b) -> a.at.isBefore(b.at) })
    }

    @Test
    fun `getNavCurve DAY keeps the last 24 hours, sorts ascending and parses both timestamp formats`() = runTest {
        coEvery { portfolioApi.getValueHistory(any(), any(), any(), any()) } returns history(
            point("2026-09-29T09:55:00+00:00", "102.00"),
            point("2026-09-28T09:59:59Z", "99.00"), // 24 h + 1 s avant « maintenant » : écarté
            point("2026-09-28T10:00:00Z", "101.00"), // borne incluse
        )

        val curve = repo().getNavCurve("p1", PnlPeriod.DAY).getOrThrow()

        assertEquals(
            listOf(Instant.parse("2026-09-28T10:00:00Z"), Instant.parse("2026-09-29T09:55:00Z")),
            curve.points.map { it.at },
        )
        assertEquals(listOf(BigDecimal("101.00"), BigDecimal("102.00")), curve.points.map { it.value })
    }

    @Test
    fun `getNavCurve skips unreadable points and returns an empty curve for an empty history`() = runTest {
        coEvery { portfolioApi.getValueHistory(any(), any(), any(), any()) } returns history(
            point("not-a-date", "10.00"),
            point("2026-09-29T09:00:00Z", null),
            point("2026-09-29T09:30:00Z", "12.50"),
        )
        val curve = repo().getNavCurve("p1", PnlPeriod.WEEK).getOrThrow()
        assertEquals(listOf(BigDecimal("12.50")), curve.points.map { it.value })

        coEvery { portfolioApi.getValueHistory(any(), any(), any(), any()) } returns history()
        assertTrue(repo().getNavCurve("p1", PnlPeriod.WEEK).getOrThrow().points.isEmpty())
    }

    @Test
    fun `getNavCurve fails on an HTTP error`() = runTest {
        coEvery { portfolioApi.getValueHistory(any(), any(), any(), any()) } returns
            Response.error(404, "not found".toResponseBody(null))

        assertTrue(repo().getNavCurve("p1", PnlPeriod.DAY).isFailure)
    }

    // ── downsample / navCurveWindow (fonctions pures) ────────────────────────────

    @Test
    fun `downsample leaves a short list untouched and always keeps first and last`() {
        assertEquals(listOf(1, 2, 3), downsample(listOf(1, 2, 3), 120))

        val sampled = downsample((1..1000).toList(), 120)

        assertEquals(120, sampled.size)
        assertEquals(1, sampled.first())
        assertEquals(1000, sampled.last())
        assertTrue(sampled.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun `navCurveWindow DAY is a rolling 24 hours in raw granularity`() {
        val window = navCurveWindow(PnlPeriod.DAY, Instant.parse("2026-09-29T00:30:00Z"))

        assertEquals("2026-09-28", window.startDate.toString())
        assertEquals("raw", window.granularity)
        assertEquals(Instant.parse("2026-09-28T00:30:00Z"), window.keepFrom)
    }

    // ── getBrokerStatus ──────────────────────────────────────────────────────────

    @Test
    fun `getBrokerStatus returns null when the backend body is null (no connection)`() = runTest {
        coEvery { portfolioApi.getBrokerConnection("p1") } returns
            Response.success<PortfolioBrokerConnectionDto?>(null)

        val result = repo().getBrokerStatus("p1")

        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
    }

    @Test
    fun `getBrokerStatus maps the broker code and the configuration status`() = runTest {
        coEvery { portfolioApi.getBrokerConnection("p1") } returns Response.success<PortfolioBrokerConnectionDto?>(
            PortfolioBrokerConnectionDto(brokerCode = "alpaca", connectionStatus = "active"),
        )

        val status = repo().getBrokerStatus("p1").getOrThrow()

        assertNotNull(status)
        assertEquals(PortfolioBrokerStatus(brokerCode = "alpaca", connectionStatus = "active"), status)
    }

    @Test
    fun `getBrokerStatus fails on an HTTP error`() = runTest {
        coEvery { portfolioApi.getBrokerConnection(any()) } returns
            Response.error(403, "denied".toResponseBody(null))

        assertTrue(repo().getBrokerStatus("p1").isFailure)
    }
}
