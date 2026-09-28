package com.tradingplatform.app.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * PR 2.4 (audit finding #9 / B-dto-3, audit/verified.md) — [parseInstantLenient] /
 * [parseInstantOrNull] must accept every timestamp shape the backend actually emits
 * (`.isoformat()` → `Z` or a numeric offset, with or without fractional seconds) and never
 * regress the plain `Z` case `Instant.parse` already handled.
 */
class InstantParsingTest {

    @Test
    fun `Z suffix parses via the fast path`() {
        val result = parseInstantLenient("2026-05-04T10:00:00Z")

        assertEquals(Instant.parse("2026-05-04T10:00:00Z"), result)
    }

    @Test
    fun `zero numeric offset +00 00 normalises to the same instant as Z`() {
        val result = parseInstantLenient("2026-05-04T10:00:00+00:00")

        assertEquals(Instant.parse("2026-05-04T10:00:00Z"), result)
    }

    @Test
    fun `non-UTC numeric offset +02 00 is normalised to UTC`() {
        val result = parseInstantLenient("2026-05-04T12:00:00+02:00")

        assertEquals(Instant.parse("2026-05-04T10:00:00Z"), result)
    }

    @Test
    fun `microsecond fraction with +00 00 offset parses`() {
        val result = parseInstantLenient("2026-05-04T10:00:00.123456+00:00")

        assertEquals(Instant.parse("2026-05-04T10:00:00.123456Z"), result)
    }

    @Test
    fun `naive local date-time with no offset is interpreted as UTC`() {
        val result = parseInstantLenient("2026-05-04T10:00:00")

        assertEquals(Instant.parse("2026-05-04T10:00:00Z"), result)
    }

    @Test(expected = DateTimeParseException::class)
    fun `garbage input throws DateTimeParseException`() {
        parseInstantLenient("not-a-timestamp")
    }

    @Test
    fun `parseInstantOrNull returns null for a null string`() {
        val raw: String? = null

        assertNull(raw.parseInstantOrNull())
    }

    @Test
    fun `parseInstantOrNull returns null for garbage instead of throwing`() {
        assertNull("not-a-timestamp".parseInstantOrNull())
    }

    @Test
    fun `parseInstantOrNull accepts the same lenient shapes as parseInstantLenient`() {
        assertEquals(
            Instant.parse("2026-05-04T10:00:00Z"),
            "2026-05-04T12:00:00+02:00".parseInstantOrNull(),
        )
    }
}
