package com.tradingplatform.app.data.model

import com.tradingplatform.app.data.local.db.entity.PnlSnapshotEntity
import com.tradingplatform.app.domain.model.PnlPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * Units at the DTO → domain / Room boundary (audit #4, #11): the domain carries
 * **fractions** everywhere. `/performance.max_drawdown` and `/pnl.total_pnl_percent`
 * arrive as percentages and are converted by the mappers.
 */
class MappersTest {

    private val performanceDto = PerformanceResponseDto(
        totalReturn = BigDecimal("4500.00"),
        totalReturnPct = 0.045,
        sharpeRatio = 1.2,
        sortinoRatio = 1.5,
        maxDrawdown = 8.3,
        volatility = 0.12,
        cagr = 0.08,
        winRate = 0.6,
        profitFactor = 1.4,
        avgTradeReturn = BigDecimal("12.00"),
    )

    private val pnlDto = PnlResponseDto(
        period = "week",
        realizedPnl = BigDecimal("120.50"),
        unrealizedPnl = BigDecimal("-20.25"),
        totalPnl = BigDecimal("100.25"),
        totalPnlPercent = 1.75,
        tradesCount = 4,
        winningTrades = 3,
        losingTrades = 1,
    )

    // ── /performance ────────────────────────────────────────────────────────────

    @Test
    fun `performance max_drawdown percent is converted to a fraction`() {
        val metrics = performanceDto.toPerformanceMetrics()

        assertEquals(0.083, metrics.maxDrawdown!!, 1e-9)
    }

    @Test
    fun `performance fields already in fractions are passed through`() {
        val metrics = performanceDto.toPerformanceMetrics()

        assertEquals(0.045, metrics.totalReturnPct!!, 1e-9)
        assertEquals(0.12, metrics.volatility!!, 1e-9)
        assertEquals(0.08, metrics.cagr!!, 1e-9)
        assertEquals(0.6, metrics.winRate!!, 1e-9)
    }

    @Test
    fun `performance null max_drawdown stays null`() {
        assertNull(performanceDto.copy(maxDrawdown = null).toPerformanceMetrics().maxDrawdown)
    }

    // ── /pnl → Room ─────────────────────────────────────────────────────────────

    @Test
    fun `pnl dto to entity stores total_pnl_percent as a fraction keyed on the requested period`() {
        val entity = pnlDto.toEntity(PnlPeriod.WEEK, syncedAt = 1_000L)

        assertEquals("week", entity.period)
        assertEquals("120.50", entity.realizedPnl)
        assertEquals("-20.25", entity.unrealizedPnl)
        assertEquals("100.25", entity.totalPnl)
        assertEquals(0.0175, entity.totalPnlPercent, 1e-9)
        assertEquals(4, entity.tradesCount)
        assertEquals(3, entity.winningTrades)
        assertEquals(1, entity.losingTrades)
        assertEquals(1_000L, entity.syncedAt)
    }

    @Test
    fun `pnl YEAR entity is keyed on ytd`() {
        assertEquals("ytd", pnlDto.toEntity(PnlPeriod.YEAR, syncedAt = 0L).period)
    }

    // ── Room → domain ───────────────────────────────────────────────────────────

    @Test
    fun `pnl entity to domain keeps the fraction and never derives a winRate`() {
        val summary = pnlDto.toEntity(PnlPeriod.DAY, syncedAt = 0L).toDomain()

        assertEquals(BigDecimal("100.25"), summary.totalReturn)
        assertEquals(0.0175, summary.totalReturnPct!!, 1e-9)
        // winning_trades / trades_count n'est pas un win rate (contrat backend §8.1 / §9).
        assertNull(summary.winRate)
        assertEquals(4, summary.tradesCount)
        assertEquals(3, summary.winningTrades)
        assertEquals(1, summary.losingTrades)
        assertNull(summary.sharpeRatio)
        assertNull(summary.maxDrawdown)
    }

    @Test
    fun `pnl entity with zero trades has a null winRate`() {
        val entity = PnlSnapshotEntity(
            period = "day",
            realizedPnl = "0",
            unrealizedPnl = "0",
            totalPnl = "0",
            totalPnlPercent = 0.0,
            tradesCount = 0,
            winningTrades = 0,
            losingTrades = 0,
            syncedAt = 0L,
        )

        assertNull(entity.toDomain().winRate)
    }

    @Test
    fun `pnl dto summary and cached summary agree`() {
        val live = pnlDto.toPnlSummary()
        val cached = pnlDto.toEntity(PnlPeriod.DAY, syncedAt = 0L).toDomain()

        assertEquals(live.totalReturn, cached.totalReturn)
        assertEquals(live.totalReturnPct!!, cached.totalReturnPct!!, 1e-12)
        assertNull(live.winRate)
        assertNull(cached.winRate)
    }

    // ── PnlPeriod ───────────────────────────────────────────────────────────────

    @Test
    fun `PnlPeriod api strings match the backend pnl route`() {
        assertEquals("day", PnlPeriod.DAY.toApiString())
        assertEquals("week", PnlPeriod.WEEK.toApiString())
        assertEquals("month", PnlPeriod.MONTH.toApiString())
        assertEquals("ytd", PnlPeriod.YEAR.toApiString())
        assertEquals("all", PnlPeriod.ALL.toApiString())
        PnlPeriod.entries.forEach { assertEquals(it, PnlPeriod.fromApiString(it.toApiString())) }
        assertNull(PnlPeriod.fromApiString("year"))
    }

    // ── PositionDto → Position (PR-5c FINDING / PR-2.5 fix) ──────────────────────

    /**
     * `PositionResponse.quantity`/`average_price` are `Optional[str]` server-side
     * (app/portfolio/schemas.py:980-981) — the backend can legally omit or null them.
     * `PositionDto` mirrors that nullability; the mapper coerces null to `BigDecimal.ZERO`
     * so the non-nullable domain `Position` contract holds without a Moshi decode crash.
     */
    @Test
    fun `PositionDto null quantity and average_price map to ZERO, not a crash`() {
        val dto = PositionDto(
            id = 1,
            symbol = "TSLA",
            quantity = null,
            avgPrice = null,
        )

        val position = dto.toDomain()

        assertEquals(BigDecimal.ZERO, position.quantity)
        assertEquals(BigDecimal.ZERO, position.avgPrice)
    }

    @Test
    fun `PositionDto non-null quantity and average_price pass through unchanged`() {
        val dto = PositionDto(
            id = 1,
            symbol = "TSLA",
            quantity = BigDecimal("10"),
            avgPrice = BigDecimal("250.00"),
        )

        val position = dto.toDomain()

        assertEquals(BigDecimal("10"), position.quantity)
        assertEquals(BigDecimal("250.00"), position.avgPrice)
    }

    // ── DeviceDto → Device (PR-5c FINDING / PR-2.5 fix) ──────────────────────────

    /**
     * `hostname`/`scrapers_circuit`/`available_memory_mb` were never fields of
     * `DeviceResponse` (trading-platform2 app/edge/schemas.py:186-221) — removed from
     * `DeviceDto`; the domain `Device` keeps them nullable and the mapper sets them to
     * null explicitly rather than propagating stale/fabricated values.
     */
    @Test
    fun `DeviceDto toDomain sets hostname, scrapersCircuit and availableMemoryMb to null`() {
        val dto = DeviceDto(
            id = "device-1",
            status = "online",
            lastHeartbeat = null,
        )

        val device = dto.toDomain()

        assertNull(device.hostname)
        assertNull(device.scrapersCircuit)
        assertNull(device.availableMemoryMb)
    }
}
