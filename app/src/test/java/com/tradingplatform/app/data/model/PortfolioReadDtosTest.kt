package com.tradingplatform.app.data.model

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Désérialisation Moshi réelle (adapters générés) des payloads du contrat backend
 * (`scratchpad/backend-contracts.md` §3, §7, §8) + mappers de `PortfolioReadMappers.kt`.
 */
class PortfolioReadDtosTest {

    private val moshi = Moshi.Builder().build()

    @Test
    fun `batch pnl payload keeps pnl_pct as the raw fraction and tolerates a null currency`() {
        val json = """
            {"data": {
              "7d1c1f0e": {"portfolio_id": "7d1c1f0e", "pnl_amount": "-84.31", "pnl_pct": -0.00826,
                           "previous_value": "10158.31", "current_value": "10074.00", "currency_code": "EUR"},
              "ffffffff": {"portfolio_id": "ffffffff", "pnl_amount": "0", "pnl_pct": 0,
                           "previous_value": "0", "current_value": "0", "currency_code": null}
            }}
        """.trimIndent()

        val dto = moshi.adapter(BatchPnlResponseDto::class.java).fromJson(json)!!

        val known = dto.data.getValue("7d1c1f0e")
        assertEquals("-84.31", known.pnlAmount)
        assertEquals(-0.00826, known.pnlPct!!, 1e-12)
        assertEquals(-0.00826, known.pnlFraction()!!, 1e-12)
        assertEquals("EUR", known.currencyCode)
        assertNull(dto.data.getValue("ffffffff").currencyCode)
    }

    @Test
    fun `batch pnl request serializes ids and period with the backend key names`() {
        val json = moshi.adapter(BatchPnlRequestDto::class.java)
            .toJson(BatchPnlRequestDto(listOf("a", "b"), "week"))

        assertEquals("""{"portfolio_ids":["a","b"],"period":"week"}""", json)
    }

    @Test
    fun `a JSON null broker-connection body deserializes to null`() {
        val adapter = moshi.adapter(PortfolioBrokerConnectionDto::class.java)

        assertNull(adapter.fromJson("null"))

        val dto = adapter.fromJson(
            """{"id": 37, "broker_code": "alpaca", "api_key_hint": "PKAB****", "connection_status": "active"}""",
        )
        assertNotNull(dto)
        assertEquals("alpaca", dto!!.brokerCode)
        assertEquals("active", dto.connectionStatus)
        assertEquals("alpaca", dto.toDomain().brokerCode)
    }

    @Test
    fun `value history items map to nav points for both Z and numeric-offset timestamps`() {
        val json = """
            {"items": [
              {"id": 1, "total_value": "10120.55", "cash_value": "2100.00", "daily_pnl": "-12.40",
               "recorded_at": "2026-09-28T21:00:04.512345Z"},
              {"id": 2, "total_value": "10130.00", "recorded_at": "2026-09-29T09:00:00+00:00"}
            ], "total": 2}
        """.trimIndent()

        val points = moshi.adapter(ValueHistoryResponseDto::class.java).fromJson(json)!!
            .items.map { it.toNavPointOrNull()!! }

        assertEquals(BigDecimal("10120.55"), points[0].value)
        assertEquals(Instant.parse("2026-09-28T21:00:04.512345Z"), points[0].at)
        assertEquals(Instant.parse("2026-09-29T09:00:00Z"), points[1].at)
    }

    @Test
    fun `dashboard overview payload exposes current value and initial capital as strings`() {
        val json = """
            {"portfolios": [{"id": "7d1c1f0e", "name": "Growth EUR", "currency_code": "EUR",
                             "current_value": "10074.00", "initial_capital": "10000.00", "is_paper": false,
                             "today_pnl": "-84.31", "today_pnl_pct": -0.826}],
             "active_orders_count": 2, "total_value": "10074.00"}
        """.trimIndent()

        val portfolio = moshi.adapter(DashboardOverviewDto::class.java).fromJson(json)!!.portfolios.single()

        assertEquals("7d1c1f0e", portfolio.id)
        assertEquals("10074.00", portfolio.currentValue)
        assertEquals("10000.00", portfolio.initialCapital)
    }

    @Test
    fun `pnlFraction never divides the batch fraction by 100 and derives it only when absent`() {
        val given = BatchPnlItemDto(pnlAmount = "1", pnlPct = 0.045, previousValue = "100", currentValue = "101")
        assertEquals(0.045, given.pnlFraction()!!, 1e-12)

        val derived = given.copy(pnlPct = null, pnlAmount = "5.00", previousValue = "100.00")
        assertEquals(0.05, derived.pnlFraction()!!, 1e-12)

        val notFinite = given.copy(pnlPct = Double.NaN, previousValue = "0")
        assertNull(notFinite.pnlFraction())
    }
}
