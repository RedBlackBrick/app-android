package com.tradingplatform.app.ui.screens.orders

import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.Order
import com.tradingplatform.app.domain.model.OrderSide
import com.tradingplatform.app.domain.model.OrderStatus
import com.tradingplatform.app.domain.model.OrderType
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.vpn.VpnNotConnectedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

/** Règles pures de l'écran Ordres : annulabilité, récapitulatif et messages d'annulation. */
class OrdersPresentationTest {

    private fun order(
        id: Long = 7L,
        symbol: String = "AAPL",
        side: OrderSide = OrderSide.SELL,
        quantity: BigDecimal? = BigDecimal("10.500"),
        status: OrderStatus? = OrderStatus.SUBMITTED,
    ): Order = Order(
        id = id,
        symbol = symbol,
        side = side,
        quantity = quantity,
        orderType = OrderType.LIMIT,
        status = status,
        filledQuantity = BigDecimal.ZERO,
        averageFillPrice = null,
        limitPrice = BigDecimal("182.5"),
        stopPrice = null,
        portfolioId = "p1",
        brokerOrderId = null,
        createdAt = null,
        updatedAt = null,
    )

    // ── Annulabilité ─────────────────────────────────────────────────────────────

    @Test
    fun `only pending, submitted, partial, rollover and retry orders get a cancel button`() {
        val cancellable = listOf(
            OrderStatus.PENDING,
            OrderStatus.SUBMITTED,
            OrderStatus.PARTIAL,
            OrderStatus.ROLLOVER_PENDING,
            OrderStatus.PENDING_RETRY,
        )
        val notCancellable = listOf(
            OrderStatus.PENDING_APPROVAL,
            OrderStatus.PENDING_CANCEL,
            OrderStatus.FILLED,
            OrderStatus.CANCELLED,
            OrderStatus.REJECTED,
            OrderStatus.EXPIRED,
            OrderStatus.ERROR,
            OrderStatus.UNKNOWN,
        )

        cancellable.forEach { assertTrue("$it should be cancellable", it.isCancellable()) }
        notCancellable.forEach { assertFalse("$it should not be cancellable", it.isCancellable()) }
        val missing: OrderStatus? = null
        assertFalse(missing.isCancellable())
        // Un nouveau statut doit être classé explicitement dans l'une des deux listes.
        assertEquals(OrderStatus.entries.toSet(), (cancellable + notCancellable).toSet())
    }

    // ── Récapitulatif de confirmation ────────────────────────────────────────────

    @Test
    fun `the confirmation is destructive and recaps symbol, side and quantity with the broker warning`() {
        val action = cancelConfirmAction(order())

        assertEquals("Demander l'annulation de l'ordre ?", action.title)
        assertEquals("Demander l'annulation", action.confirmLabel)
        assertTrue(action.destructive)
        assertFalse(action.requireReason)
        assertEquals(
            listOf(
                "Symbole" to "AAPL",
                "Sens" to "Vente",
                "Quantité" to "10.5",
            ),
            action.summaryLines,
        )
        assertEquals("Le broker peut refuser ou ignorer l'annulation.", action.message)
    }

    @Test
    fun `the recap quantity has no trailing zeros nor scientific notation`() {
        assertEquals("100", cancelQuantityText(order(quantity = BigDecimal("100"))))
        assertEquals("0.25", cancelQuantityText(order(quantity = BigDecimal("0.2500"))))
        assertEquals("—", cancelQuantityText(order(quantity = null)))
    }

    @Test
    fun `side labels are French`() {
        assertEquals("Achat", orderSideLabel(OrderSide.BUY))
        assertEquals("Vente", orderSideLabel(OrderSide.SELL))
    }

    // ── Messages ─────────────────────────────────────────────────────────────────

    @Test
    fun `a request is worded as demanded and never as cancelled`() {
        assertEquals(
            "Annulation demandée — vérification en cours",
            cancelRequestedMessage(WriteOutcome.CONFIRMED),
        )
        assertEquals(
            "Annulation demandée — état non confirmé, vérification en cours",
            cancelRequestedMessage(WriteOutcome.REQUESTED_UNCONFIRMED),
        )
        assertEquals("Annulation demandée", CANCEL_REQUESTED_LABEL)
    }

    @Test
    fun `the re-read message reports what the server says without claiming a cancellation`() {
        assertEquals(
            "Annulation demandée — l'ordre n'est plus dans les ordres actifs",
            cancelRereadMessage(7L, listOf(order(id = 8L))),
        )
        assertEquals(
            "Annulation demandée — en cours de traitement",
            cancelRereadMessage(7L, listOf(order(id = 7L, status = OrderStatus.PENDING_CANCEL))),
        )
        assertEquals(
            "Annulation demandée — l'ordre est toujours actif",
            cancelRereadMessage(7L, listOf(order(id = 7L, status = OrderStatus.SUBMITTED))),
        )
    }

    @Test
    fun `certain failures are explained`() {
        val endpoint = "v1/orders/{order_id}/cancel"

        assertEquals(
            "Cet ordre n'est plus annulable — liste actualisée.",
            cancelFailureMessage(HttpStatusException(409, endpoint)),
        )
        assertEquals(
            "Annulation refusée par le serveur (requête invalide ou broker injoignable).",
            cancelFailureMessage(HttpStatusException(400, endpoint)),
        )
        assertEquals(
            "Annulation refusée — session expirée ou droits insuffisants.",
            cancelFailureMessage(HttpStatusException(401, endpoint)),
        )
        assertEquals(
            "Annulation refusée — session expirée ou droits insuffisants.",
            cancelFailureMessage(HttpStatusException(403, endpoint)),
        )
        assertEquals(
            "Trop de demandes — réessayez dans un instant.",
            cancelFailureMessage(HttpStatusException(429, endpoint)),
        )
        assertEquals(
            "Annulation refusée par le serveur (HTTP 422).",
            cancelFailureMessage(HttpStatusException(422, endpoint)),
        )
        assertEquals(
            "VPN requis — activez le tunnel puis réessayez.",
            cancelFailureMessage(VpnNotConnectedException()),
        )
        assertEquals(
            "Annulation impossible — vérifiez la connexion puis réessayez.",
            cancelFailureMessage(IOException("boom")),
        )
    }

    @Test
    fun `the cancel button is announced with the side and the symbol`() {
        assertEquals(
            "Demander l'annulation de l'ordre Vente AAPL",
            cancelButtonDescription(order()),
        )
        assertEquals(
            "Demander l'annulation de l'ordre Achat TSLA",
            cancelButtonDescription(order(symbol = "TSLA", side = OrderSide.BUY)),
        )
    }
}
