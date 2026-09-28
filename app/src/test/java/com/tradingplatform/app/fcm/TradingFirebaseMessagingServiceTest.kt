package com.tradingplatform.app.fcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests unitaires pour [TradingFirebaseMessagingService.notificationId] (PR 4.5 — audit #19,
 * candidate D-fcm-correctness-2).
 *
 * Fonction pure — pas de dépendance Android, pas besoin de Robolectric.
 */
class TradingFirebaseMessagingServiceTest {

    @Test
    fun `notificationId is deterministic for identical inputs`() {
        val id1 = TradingFirebaseMessagingService.notificationId(
            type = "PRICE_ALERT",
            receivedAt = 1_700_000_000_000L,
            title = "AAPL",
            body = "Price crossed 200",
        )
        val id2 = TradingFirebaseMessagingService.notificationId(
            type = "PRICE_ALERT",
            receivedAt = 1_700_000_000_000L,
            title = "AAPL",
            body = "Price crossed 200",
        )

        assertEquals(id1, id2)
    }

    @Test
    fun `notificationId differs when the type differs`() {
        val id1 = TradingFirebaseMessagingService.notificationId(
            type = "PRICE_ALERT", receivedAt = 1L, title = "t", body = "b",
        )
        val id2 = TradingFirebaseMessagingService.notificationId(
            type = "TRADE_EXECUTED", receivedAt = 1L, title = "t", body = "b",
        )

        assertNotEquals(id1, id2)
    }

    @Test
    fun `notificationId differs when the received timestamp differs`() {
        val id1 = TradingFirebaseMessagingService.notificationId(
            type = "PRICE_ALERT", receivedAt = 1_000L, title = "t", body = "b",
        )
        val id2 = TradingFirebaseMessagingService.notificationId(
            type = "PRICE_ALERT", receivedAt = 2_000L, title = "t", body = "b",
        )

        assertNotEquals(id1, id2)
    }

    @Test
    fun `notificationId differs when the title or body differs`() {
        val base = TradingFirebaseMessagingService.notificationId(
            type = "PRICE_ALERT", receivedAt = 1L, title = "AAPL", body = "up",
        )
        val differentTitle = TradingFirebaseMessagingService.notificationId(
            type = "PRICE_ALERT", receivedAt = 1L, title = "TSLA", body = "up",
        )
        val differentBody = TradingFirebaseMessagingService.notificationId(
            type = "PRICE_ALERT", receivedAt = 1L, title = "AAPL", body = "down",
        )

        assertNotEquals(base, differentTitle)
        assertNotEquals(base, differentBody)
    }
}
