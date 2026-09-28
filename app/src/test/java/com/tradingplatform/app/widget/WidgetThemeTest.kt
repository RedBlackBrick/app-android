package com.tradingplatform.app.widget

import com.tradingplatform.app.data.local.datastore.SecureReadResult
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.data.local.db.entity.PositionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Audit #18 / D-widgets-di-1 — les widgets affichaient un simple "HH:mm" au-delà de 60 min :
 * une donnée vieille de 3 jours apparaissait « Sync 14:32 » comme si elle était du jour.
 * Tests JVM purs (pas d'Android) des helpers de widgets.
 */
class WidgetThemeTest {

    private val zone: ZoneId = ZoneOffset.UTC

    /** 2026-09-28 15:00:00 UTC */
    private val now = LocalDateTime.of(2026, 9, 28, 15, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun minutesAgo(min: Long) = now - min * 60_000L

    private fun label(syncedAt: Long, ttlMs: Long = CacheTtl.QUOTES_MS) =
        formatWidgetSyncTime(syncedAt, ttlMs, now = now, zone = zone)

    // ── formatWidgetSyncTime ──────────────────────────────────────────────────

    @Test
    fun `under one minute reads maintenant`() {
        assertEquals(SyncLabel("maintenant", isStale = false), label(now - 30_000L))
    }

    @Test
    fun `under one hour reads il y a N min`() {
        assertEquals(SyncLabel("il y a 7min", isStale = false), label(minutesAgo(7)))
    }

    @Test
    fun `same day beyond one hour reads HH mm`() {
        val result = label(minutesAgo(150), ttlMs = Long.MAX_VALUE)  // 12:30 le même jour
        assertEquals(SyncLabel("12:30", isStale = false), result)
    }

    @Test
    fun `previous day reads dd MM HH mm`() {
        val threeDaysAgo = LocalDateTime.of(2026, 9, 25, 14, 32).toInstant(ZoneOffset.UTC).toEpochMilli()
        val result = label(threeDaysAgo, ttlMs = Long.MAX_VALUE)
        assertEquals(SyncLabel("25/09 14:32", isStale = false), result)
    }

    @Test
    fun `yesterday late evening is not shown as today's time`() {
        val yesterday = LocalDateTime.of(2026, 9, 27, 23, 50).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("27/09 23:50", label(yesterday, ttlMs = Long.MAX_VALUE).text)
    }

    @Test
    fun `older than ttl is prefixed perime and flagged stale`() {
        val result = label(minutesAgo(12), ttlMs = CacheTtl.QUOTES_MS)
        assertEquals(SyncLabel("périmé · il y a 12min", isStale = true), result)
    }

    @Test
    fun `stale multi-day data carries both the prefix and the date`() {
        val threeDaysAgo = LocalDateTime.of(2026, 9, 25, 14, 32).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(SyncLabel("périmé · 25/09 14:32", isStale = true), label(threeDaysAgo, CacheTtl.POSITIONS_MS))
    }

    @Test
    fun `exactly at ttl is not stale yet`() {
        assertFalse(label(now - CacheTtl.DEVICES_MS, ttlMs = CacheTtl.DEVICES_MS).isStale)
        assertTrue(label(now - CacheTtl.DEVICES_MS - 1, ttlMs = CacheTtl.DEVICES_MS).isStale)
    }

    @Test
    fun `future timestamp from clock skew reads maintenant and is fresh`() {
        assertEquals(SyncLabel("maintenant", isStale = false), label(now + 5 * 60_000L))
    }

    // ── Seuil widget = maxOf(TTL entité, WIDGET_STALE_GRACE_MS) (worker 15 min) ─

    @Test
    fun `widget stale threshold is floored at the worker period plus grace`() {
        assertEquals(20 * 60_000L, CacheTtl.WIDGET_STALE_GRACE_MS)
        assertEquals(CacheTtl.WIDGET_STALE_GRACE_MS, widgetStaleThreshold(CacheTtl.POSITIONS_MS))
        assertEquals(CacheTtl.WIDGET_STALE_GRACE_MS, widgetStaleThreshold(CacheTtl.PNL_MS))
        assertEquals(CacheTtl.WIDGET_STALE_GRACE_MS, widgetStaleThreshold(CacheTtl.QUOTES_MS))
        assertEquals(CacheTtl.WIDGET_STALE_GRACE_MS, widgetStaleThreshold(CacheTtl.DEVICES_MS))
        assertEquals(60 * 60_000L, widgetStaleThreshold(60 * 60_000L))  // TTL plus long conservé
    }

    @Test
    fun `positions synced one worker cycle ago are not stale on a widget`() {
        // 14 min > POSITIONS_MS (5 min) mais < 20 min : pas de badge entre deux cycles du Worker
        val result = label(minutesAgo(14), ttlMs = widgetStaleThreshold(CacheTtl.POSITIONS_MS))
        assertEquals(SyncLabel("il y a 14min", isStale = false), result)
    }

    @Test
    fun `widget data older than the grace period is stale`() {
        val result = label(minutesAgo(21), ttlMs = widgetStaleThreshold(CacheTtl.QUOTES_MS))
        assertEquals(SyncLabel("périmé · il y a 21min", isStale = true), result)
    }

    @Test
    fun `sync prefix is dropped when stale`() {
        assertEquals("Sync il y a 2min", label(minutesAgo(2)).withSyncPrefix())
        assertEquals("périmé · il y a 20min", label(minutesAgo(20)).withSyncPrefix())
    }

    // ── PositionsWidget — top 5 par exposition absolue (D-widgets-correctness-2) ─

    private fun position(
        id: Int,
        symbol: String,
        quantity: String,
        avgPrice: String,
        currentPrice: String? = null,
        status: String = "open",
    ) = PositionEntity(
        id = id,
        symbol = symbol,
        quantity = quantity,
        avgPrice = avgPrice,
        currentPrice = currentPrice,
        unrealizedPnl = null,
        unrealizedPnlPercent = null,
        status = status,
        openedAt = null,
        syncedAt = now,
    )

    @Test
    fun `top positions are sorted by absolute exposure, not alphabetically`() {
        val positions = listOf(
            position(1, "AAPL", "1", "100"),                        // 100
            position(2, "BTC", "0.5", "30000", currentPrice = "40000"), // 20 000 (prix courant)
            position(3, "MSFT", "-50", "300"),                      // |−15 000| (short)
            position(4, "NVDA", "10", "500"),                       // 5 000
            position(5, "TSLA", "20", "200"),                       // 4 000
            position(6, "AMD", "5", "100"),                         // 500
        )

        val top = topPositionsByExposure(positions)

        assertEquals(listOf("BTC", "MSFT", "NVDA", "TSLA", "AMD"), top.map { it.symbol })
    }

    @Test
    fun `closed positions are excluded from the widget`() {
        val positions = listOf(
            position(1, "AAPL", "1000", "100", status = "closed"),
            position(2, "MSFT", "1", "10"),
        )

        assertEquals(listOf("MSFT"), topPositionsByExposure(positions).map { it.symbol })
    }

    @Test
    fun `an unparseable row is kept but ranked last`() {
        val positions = listOf(
            position(1, "BAD", "n/a", "100"),
            position(2, "GOOD", "1", "10"),
        )

        assertEquals(listOf("GOOD", "BAD"), topPositionsByExposure(positions).map { it.symbol })
    }

    // ── SystemStatusWidget — is_admin via readBooleanSafe (D-widgets-correctness-1) ─

    @Test
    fun `admin access maps corrupted datastore to session expired`() {
        assertEquals(AdminAccess.GRANTED, adminAccessOf(SecureReadResult.Found(true)))
        assertEquals(AdminAccess.NOT_ADMIN, adminAccessOf(SecureReadResult.Found(false)))
        assertEquals(AdminAccess.NOT_ADMIN, adminAccessOf(SecureReadResult.NotFound))
        assertEquals(
            AdminAccess.SESSION_EXPIRED,
            adminAccessOf(SecureReadResult.Corrupted(java.io.IOException("corrupt"))),
        )
    }
}
