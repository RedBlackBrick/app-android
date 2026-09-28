package com.tradingplatform.app.data.repository

import app.cash.turbine.test
import com.tradingplatform.app.data.websocket.PrivateWsClient
import com.tradingplatform.app.data.websocket.WsEvent
import com.tradingplatform.app.domain.model.WsConnectionState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies [WsRepository]'s JSON -> [com.tradingplatform.app.domain.model.WsUpdate]
 * mapping against payloads copied verbatim from the backend
 * (`trading-platform2/app/portfolio/consumer.py`), built on every order execution.
 *
 * See finding #23 (`position_update` field drift) and the `portfolio_update`
 * field drift documented in `audit/plan-market-data.md` §C, evidenced by rows
 * NEW-ws-position-price, C-pos-corr-1 and NEW-portfolio-update-keys in
 * `audit/verified.md`.
 *
 * `ws_position_payload` (consumer.py ~L2164-2199): portfolio_id, symbol, side,
 * quantity, average_price, last_price, unrealized_pnl, realized_pnl, is_active
 * — NO position_id.
 *
 * `ws_payload` (consumer.py ~L2151-2160): portfolio_id, symbol, side, quantity,
 * price, total_value, cash_balance, positions_value — NO nav/daily_pnl/total_pnl.
 */
@ExperimentalCoroutinesApi
class WsRepositoryTest {

    private val privateWsClient = mockk<PrivateWsClient>()
    private val events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 4)

    private lateinit var repository: WsRepository

    @Before
    fun setUp() {
        every { privateWsClient.events } returns events
        every { privateWsClient.connectionState } returns MutableStateFlow(WsConnectionState.Connected)
        repository = WsRepository(privateWsClient)
    }

    // ── position_update ──────────────────────────────────────────────────────

    @Test
    fun `position_update maps every field from the real backend payload`() = runTest {
        val payload = JSONObject(
            """
            {
              "portfolio_id": 7,
              "symbol": "TSLA",
              "side": "LONG",
              "quantity": 10.0,
              "average_price": 250.0,
              "last_price": 295.5,
              "unrealized_pnl": 455.0,
              "realized_pnl": 0.0,
              "is_active": true
            }
            """.trimIndent(),
        )

        repository.positionUpdates.test {
            events.emit(WsEvent.PositionUpdate(payload))
            val update = awaitItem()

            assertNull("Backend does not send position_id yet", update.positionId)
            assertEquals("TSLA", update.symbol)
            assertEquals("LONG", update.side)
            assertEquals(10.0, update.quantity)
            assertEquals(250.0, update.averagePrice)
            assertEquals(295.5, update.lastPrice)
            assertEquals(455.0, update.unrealizedPnl)
            assertEquals(0.0, update.realizedPnl)
            assertTrue(update.isActive)
        }
    }

    @Test
    fun `position_update is_active false and null unrealized_pnl are preserved (full close)`() = runTest {
        val payload = JSONObject(
            """
            {
              "portfolio_id": 7,
              "symbol": "TSLA",
              "side": "LONG",
              "quantity": 0.0,
              "average_price": 250.0,
              "last_price": 300.0,
              "unrealized_pnl": null,
              "realized_pnl": 500.0,
              "is_active": false
            }
            """.trimIndent(),
        )

        repository.positionUpdates.test {
            events.emit(WsEvent.PositionUpdate(payload))
            val update = awaitItem()

            assertFalse(update.isActive)
            assertNull(update.unrealizedPnl)
            assertEquals(500.0, update.realizedPnl)
        }
    }

    @Test
    fun `position_update falls back to current_price when last_price is absent`() = runTest {
        // Tolerate a pre-drift payload shape — should never happen against the
        // real backend today, but keeps the mapping backward compatible.
        val payload = JSONObject(
            """
            {
              "symbol": "TSLA",
              "current_price": 288.0
            }
            """.trimIndent(),
        )

        repository.positionUpdates.test {
            events.emit(WsEvent.PositionUpdate(payload))
            assertEquals(288.0, awaitItem().lastPrice)
        }
    }

    @Test
    fun `position_update missing is_active defaults to true`() = runTest {
        val payload = JSONObject(
            """
            {
              "portfolio_id": 7,
              "symbol": "TSLA",
              "side": "LONG",
              "quantity": 10.0,
              "average_price": 250.0,
              "last_price": 295.5,
              "unrealized_pnl": 455.0,
              "realized_pnl": 0.0
            }
            """.trimIndent(),
        )

        repository.positionUpdates.test {
            events.emit(WsEvent.PositionUpdate(payload))
            assertTrue(awaitItem().isActive)
        }
    }

    // ── portfolio_update ──────────────────────────────────────────────────────

    @Test
    fun `portfolio_update maps every field from the real backend payload`() = runTest {
        // No nav/daily_pnl/total_pnl keys — the backend never sends them here.
        val payload = JSONObject(
            """
            {
              "portfolio_id": 7,
              "symbol": "TSLA",
              "side": "buy",
              "quantity": 10.0,
              "price": 295.5,
              "total_value": 125000.0,
              "cash_balance": 50000.0,
              "positions_value": 75000.0
            }
            """.trimIndent(),
        )

        repository.portfolioUpdates.test {
            events.emit(WsEvent.PortfolioUpdate(payload))
            val update = awaitItem()

            assertEquals("7", update.portfolioId)
            assertEquals("TSLA", update.symbol)
            assertEquals("buy", update.side)
            assertEquals(10.0, update.quantity)
            assertEquals(295.5, update.price)
            assertEquals(125000.0, update.totalValue)
            assertEquals(50000.0, update.cashBalance)
            assertEquals(75000.0, update.positionsValue)
            // Back-compat alias for existing consumers — not a distinct backend field.
            assertEquals(125000.0, update.nav)
            assertNull("Backend never sends daily_pnl on this event", update.dailyPnl)
            assertNull("Backend never sends total_pnl on this event", update.totalPnl)
        }
    }
}
