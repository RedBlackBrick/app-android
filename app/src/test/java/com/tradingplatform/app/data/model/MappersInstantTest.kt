package com.tradingplatform.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Audit finding #9 (B-dto-3) — `TransactionItem.executed_at` is a `str` built with
 * `.isoformat()` on the backend, i.e. `2026-05-04T10:00:00.123456+00:00` (offset, not `Z`).
 * `Mappers.kt` now does `parseInstantLenient(executedAt)` (PR 2.4,
 * `domain/util/InstantParsing.kt`) instead of a raw `Instant.parse(executedAt)`.
 *
 * NOTE: this test was GREEN on the JVM even before PR 2.4 (JDK >= 12 accepts non-Z offsets
 * in `Instant.parse`, JDK-8166138) but was expected RED on Android <= 13, whose libcore
 * java.time predates that change. `parseInstantLenient` closes that gap by falling back to
 * `OffsetDateTime.parse` when `Instant.parse` rejects the numeric offset, so this test is now
 * green on every API level — see `domain/util/InstantParsingTest.kt` for the dedicated
 * coverage of the helper itself, and the instrumented api30 job (audit/PLAN.md) for the
 * on-device regression guard.
 */
class MappersInstantTest {

    private fun transaction(executedAt: String) = TransactionDto(
        id = 1L,
        symbol = "AAPL",
        action = "BUY",
        quantity = BigDecimal("10"),
        price = BigDecimal("228.92"),
        commission = BigDecimal("1.00"),
        total = BigDecimal("2290.20"),
        executedAt = executedAt,
    )

    @Test
    fun `executed_at with +00 00 offset and microseconds maps to the right instant`() {
        val domain = transaction("2026-05-04T10:00:00.123456+00:00").toDomain()

        assertEquals(Instant.parse("2026-05-04T10:00:00.123456Z"), domain.executedAt)
    }

    @Test
    fun `executed_at with a non-UTC offset is normalised to UTC`() {
        val domain = transaction("2026-05-04T12:00:00+02:00").toDomain()

        assertEquals(Instant.parse("2026-05-04T10:00:00Z"), domain.executedAt)
    }

    @Test
    fun `executed_at in Z form still parses`() {
        val domain = transaction("2026-05-04T10:00:00Z").toDomain()

        assertEquals(Instant.parse("2026-05-04T10:00:00Z"), domain.executedAt)
    }
}
