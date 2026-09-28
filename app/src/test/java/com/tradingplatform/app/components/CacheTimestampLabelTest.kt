package com.tradingplatform.app.components

import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.ui.components.CacheFreshness
import com.tradingplatform.app.ui.components.CacheTimestampLabel
import com.tradingplatform.app.ui.components.cacheTimestampLabel
import com.tradingplatform.app.ui.components.formatCacheTime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * `CacheTimestamp` était aligné sur des seuils 1/5 min figés, sans lien avec le TTL de
 * l'entité affichée ni avec les 10 min documentés (CLAUDE.md §2). Tests JVM de la logique pure.
 */
class CacheTimestampLabelTest {

    private val minute = 60_000L

    @Test
    fun `default ttl of 10 min - neutral, warning at 5 min, offline at 10 min`() {
        val ttl = CacheTtl.DEFAULT_UI_MS
        assertEquals(CacheTimestampLabel(CacheFreshness.NEUTRAL, upToDate = true), cacheTimestampLabel(30_000L, ttl))
        assertEquals(CacheTimestampLabel(CacheFreshness.NEUTRAL, upToDate = false), cacheTimestampLabel(3 * minute, ttl))
        assertEquals(CacheTimestampLabel(CacheFreshness.WARNING, upToDate = false), cacheTimestampLabel(5 * minute, ttl))
        assertEquals(CacheTimestampLabel(CacheFreshness.WARNING, upToDate = false), cacheTimestampLabel(10 * minute - 1, ttl))
        assertEquals(CacheTimestampLabel(CacheFreshness.OFFLINE, upToDate = false), cacheTimestampLabel(10 * minute, ttl))
    }

    @Test
    fun `devices ttl of 1 min - warning after 30 s, offline after 1 min`() {
        val ttl = CacheTtl.DEVICES_MS
        assertEquals(CacheTimestampLabel(CacheFreshness.NEUTRAL, upToDate = true), cacheTimestampLabel(10_000L, ttl))
        assertEquals(CacheTimestampLabel(CacheFreshness.WARNING, upToDate = false), cacheTimestampLabel(40_000L, ttl))
        assertEquals(CacheTimestampLabel(CacheFreshness.OFFLINE, upToDate = false), cacheTimestampLabel(minute, ttl))
    }

    @Test
    fun `positions ttl of 5 min`() {
        val ttl = CacheTtl.POSITIONS_MS
        assertEquals(CacheFreshness.NEUTRAL, cacheTimestampLabel(2 * minute, ttl).freshness)
        assertEquals(CacheFreshness.WARNING, cacheTimestampLabel(3 * minute, ttl).freshness)
        assertEquals(CacheFreshness.OFFLINE, cacheTimestampLabel(6 * minute, ttl).freshness)
    }

    @Test
    fun `explicit warn threshold is honoured`() {
        assertEquals(
            CacheFreshness.NEUTRAL,
            cacheTimestampLabel(8 * minute, ttlMs = 10 * minute, warnMs = 9 * minute).freshness,
        )
    }

    @Test
    fun `negative age from clock skew is treated as fresh`() {
        assertEquals(
            CacheTimestampLabel(CacheFreshness.NEUTRAL, upToDate = true),
            cacheTimestampLabel(-5 * minute, CacheTtl.DEFAULT_UI_MS),
        )
    }

    @Test
    fun `sync time shows the date when not today`() {
        val now = LocalDateTime.of(2026, 9, 28, 9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val sameDay = LocalDateTime.of(2026, 9, 28, 8, 15).toInstant(ZoneOffset.UTC).toEpochMilli()
        val yesterday = LocalDateTime.of(2026, 9, 27, 22, 5).toInstant(ZoneOffset.UTC).toEpochMilli()

        assertEquals("08:15", formatCacheTime(sameDay, now, ZoneOffset.UTC))
        assertEquals("27/09 22:05", formatCacheTime(yesterday, now, ZoneOffset.UTC))
    }
}
