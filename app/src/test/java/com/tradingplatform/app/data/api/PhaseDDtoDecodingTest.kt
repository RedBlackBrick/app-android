package com.tradingplatform.app.data.api

import com.squareup.moshi.Moshi
import com.tradingplatform.app.data.model.BigDecimalAdapter
import com.tradingplatform.app.data.model.InstantAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * JSON decoding sanity checks for the Phase D DTOs.
 *
 * The backend Pydantic schemas use snake_case; our DTOs map them with
 * ``@Json(name = "snake_case")``. A typo is silent at compile time but
 * would fail in production with a missing field. These tests decode
 * representative JSON samples that mirror the actual backend response
 * shape, so any wire-format drift is caught at JVM-test time.
 */
class PhaseDDtoDecodingTest {

    private lateinit var moshi: Moshi

    @Before
    fun setUp() {
        moshi = Moshi.Builder()
            .add(BigDecimalAdapter())
            .add(InstantAdapter())
            .build()
    }

    // ── PortfolioStrategyLink ──────────────────────────────────────────────

    @Test
    fun `PortfolioStrategyLinkDto decodes the fields needed for the count tile`() {
        val json = """
            {
              "id": 42,
              "portfolio_id": "11111111-1111-1111-1111-111111111111",
              "strategy_id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "allocation_pct": 33.3,
              "is_active": true,
              "alloc_mode": "percent",
              "custom_parameters": null
            }
        """.trimIndent()
        val adapter = moshi.adapter(PortfolioStrategyLinkDto::class.java)
        val dto = adapter.fromJson(json)
        assertNotNull(dto)
        assertEquals("11111111-1111-1111-1111-111111111111", dto!!.portfolioId)
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", dto.strategyId)
        assertTrue(dto.isActive)
    }

    @Test
    fun `PortfolioStrategyLinkDto defaults isActive to true when missing`() {
        // Defensive: backend always sends is_active, but our DTO has a default
        // so we never crash if a future omission slips through.
        val json = """
            {
              "portfolio_id": "p",
              "strategy_id": "s"
            }
        """.trimIndent()
        val adapter = moshi.adapter(PortfolioStrategyLinkDto::class.java)
        val dto = adapter.fromJson(json)
        assertNotNull(dto)
        assertTrue(dto!!.isActive)
    }

    // ── PortfolioCircuitBreakerStatus ──────────────────────────────────────

    @Test
    fun `PortfolioCircuitBreakerStatusDto decodes the closed-state shape`() {
        val json = """
            {
              "portfolio_id": "11111111-1111-1111-1111-111111111111",
              "enabled": true,
              "state": "closed",
              "count": 2,
              "threshold": 10,
              "window_seconds": 3600,
              "ttl_seconds": 1800,
              "redis_unavailable": false
            }
        """.trimIndent()
        val adapter = moshi.adapter(PortfolioCircuitBreakerStatusDto::class.java)
        val dto = adapter.fromJson(json)
        assertNotNull(dto)
        assertEquals("closed", dto!!.state)
        assertEquals(2, dto.count)
        assertEquals(10, dto.threshold)
        assertEquals(3600, dto.windowSeconds)
        assertEquals(1800, dto.ttlSeconds)
        assertFalse(dto.redisUnavailable)
    }

    @Test
    fun `PortfolioCircuitBreakerStatusDto decodes the redis-unavailable fail-closed shape`() {
        // Mirror of the actual fail-closed payload our backend sends when
        // Redis is unreachable: state="open", redis_unavailable=true, ttl=null.
        val json = """
            {
              "portfolio_id": "p",
              "enabled": true,
              "state": "open",
              "count": 0,
              "threshold": 10,
              "window_seconds": 3600,
              "ttl_seconds": null,
              "redis_unavailable": true
            }
        """.trimIndent()
        val adapter = moshi.adapter(PortfolioCircuitBreakerStatusDto::class.java)
        val dto = adapter.fromJson(json)
        assertNotNull(dto)
        assertEquals("open", dto!!.state)
        assertTrue(dto.redisUnavailable)
        assertNull(dto.ttlSeconds)
    }

    @Test
    fun `PortfolioCircuitBreakerStatusDto decodes the no-rule-active disabled shape`() {
        val json = """
            {
              "portfolio_id": "p",
              "enabled": false,
              "state": "closed",
              "count": 0,
              "threshold": 0,
              "window_seconds": 0,
              "ttl_seconds": null,
              "redis_unavailable": false
            }
        """.trimIndent()
        val adapter = moshi.adapter(PortfolioCircuitBreakerStatusDto::class.java)
        val dto = adapter.fromJson(json)
        assertNotNull(dto)
        assertFalse(dto!!.enabled)
        assertEquals("closed", dto.state)
    }

    // ── Orders ─────────────────────────────────────────────────────────────

    @Test
    fun `OrderDto decodes a fully-populated FILLED order`() {
        val json = """
            {
              "id": 12345,
              "symbol": "AAPL",
              "side": "BUY",
              "quantity": "10",
              "order_type": "LIMIT",
              "status": "FILLED",
              "filled_quantity": "10",
              "average_fill_price": "175.50",
              "limit_price": "176.00",
              "stop_price": null,
              "portfolio_id": "11111111-1111-1111-1111-111111111111",
              "broker_order_id": "ALPACA-abc-123",
              "strategy_id": null,
              "signal_id": null,
              "created_at": "2026-05-04T10:00:00Z",
              "updated_at": "2026-05-04T10:00:05Z",
              "metadata": null
            }
        """.trimIndent()
        val adapter = moshi.adapter(OrderDto::class.java)
        val dto = adapter.fromJson(json)
        assertNotNull(dto)
        assertEquals(12345L, dto!!.id)
        assertEquals("AAPL", dto.symbol)
        assertEquals("BUY", dto.side)
        assertEquals(BigDecimal("10"), dto.quantity)
        assertEquals("LIMIT", dto.orderType)
        assertEquals("FILLED", dto.status)
        assertEquals(BigDecimal("10"), dto.filledQuantity)
        assertEquals(BigDecimal("175.50"), dto.averageFillPrice)
        assertEquals(BigDecimal("176.00"), dto.limitPrice)
        assertNull(dto.stopPrice)
        assertEquals("ALPACA-abc-123", dto.brokerOrderId)
        assertEquals("2026-05-04T10:00:00Z", dto.createdAt)
    }

    @Test
    fun `OrderDto tolerates a partially-populated PENDING order`() {
        // Backend returns nulls for unset fields on early-stage orders.
        val json = """
            {
              "id": 999,
              "symbol": "MSFT",
              "side": "SELL",
              "quantity": "5",
              "order_type": "MARKET",
              "status": "PENDING",
              "filled_quantity": "0",
              "average_fill_price": null,
              "limit_price": null,
              "stop_price": null,
              "portfolio_id": "p",
              "broker_order_id": null,
              "created_at": "2026-05-04T10:00:00Z"
            }
        """.trimIndent()
        val adapter = moshi.adapter(OrderDto::class.java)
        val dto = adapter.fromJson(json)
        assertNotNull(dto)
        assertEquals("PENDING", dto!!.status)
        assertEquals(BigDecimal("0"), dto.filledQuantity)
        assertNull(dto.averageFillPrice)
        assertNull(dto.brokerOrderId)
    }

    @Test
    fun `ActiveOrdersResponseDto decodes the wrapper`() {
        val json = """
            {
              "orders": [
                {
                  "id": 1,
                  "symbol": "AAPL",
                  "side": "BUY",
                  "order_type": "MARKET",
                  "filled_quantity": "0",
                  "portfolio_id": "p",
                  "status": "PENDING"
                }
              ],
              "count": 1
            }
        """.trimIndent()
        val adapter = moshi.adapter(ActiveOrdersResponseDto::class.java)
        val dto = adapter.fromJson(json)
        assertNotNull(dto)
        assertEquals(1, dto!!.count)
        assertEquals(1, dto.orders.size)
        assertEquals("AAPL", dto.orders[0].symbol)
    }

    @Test
    fun `OrderHistoryResponseDto decodes an empty-history payload`() {
        val json = """{"orders": [], "count": 0}"""
        val adapter = moshi.adapter(OrderHistoryResponseDto::class.java)
        val dto = adapter.fromJson(json)
        assertNotNull(dto)
        assertEquals(0, dto!!.count)
        assertTrue(dto.orders.isEmpty())
    }
}
