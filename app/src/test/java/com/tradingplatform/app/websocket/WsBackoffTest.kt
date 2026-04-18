package com.tradingplatform.app.websocket

import com.tradingplatform.app.data.websocket.WsBackoff
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests du backoff exponentiel WebSocket (CLAUDE.md §2).
 *
 * La stratégie est attendue : 5s → 10s → 20s → 40s → 80s → 160s → 300s → 300s…
 * Un bug ici se traduit par des tempêtes de reconnexions vers le VPS (batterie + charge).
 */
class WsBackoffTest {

    @Test
    fun `first attempt returns initial delay`() {
        assertEquals(5_000L, WsBackoff.computeDelayMs(attempts = 0))
    }

    @Test
    fun `backoff doubles with each attempt`() {
        assertEquals(10_000L, WsBackoff.computeDelayMs(attempts = 1))
        assertEquals(20_000L, WsBackoff.computeDelayMs(attempts = 2))
        assertEquals(40_000L, WsBackoff.computeDelayMs(attempts = 3))
        assertEquals(80_000L, WsBackoff.computeDelayMs(attempts = 4))
        assertEquals(160_000L, WsBackoff.computeDelayMs(attempts = 5))
    }

    @Test
    fun `backoff caps at max after 6 attempts`() {
        // 5s * 2^6 = 320s → capped to 300s
        assertEquals(300_000L, WsBackoff.computeDelayMs(attempts = 6))
    }

    @Test
    fun `backoff remains at max for high attempt counts`() {
        // Jamais de délai supérieur au plafond — même après 50 échecs consécutifs.
        for (attempts in 7..50) {
            assertEquals(
                300_000L,
                WsBackoff.computeDelayMs(attempts = attempts),
                "attempts=$attempts should cap to MAX_MS",
            )
        }
    }

    @Test
    fun `backoff is monotonically non-decreasing`() {
        // Propriété fondamentale : un retry ne doit JAMAIS réduire le délai
        // par rapport au précédent (sinon risque de tempête).
        var previous = 0L
        for (attempts in 0..20) {
            val current = WsBackoff.computeDelayMs(attempts = attempts)
            assertTrue(
                current >= previous,
                "Backoff decreased at attempts=$attempts : $previous -> $current",
            )
            previous = current
        }
    }

    @Test
    fun `rejects negative attempts`() {
        assertFailsWith<IllegalArgumentException> {
            WsBackoff.computeDelayMs(attempts = -1)
        }
    }

    @Test
    fun `custom parameters work as expected`() {
        // Sanity check des overrides (utile si on veut un backoff plus rapide en test).
        val fast = WsBackoff.computeDelayMs(
            attempts = 2,
            initialMs = 100L,
            maxMs = 1_000L,
            multiplier = 2.0,
        )
        assertEquals(400L, fast)  // 100 * 2^2 = 400
    }
}
