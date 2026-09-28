package com.tradingplatform.app.domain.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Tolerant ISO-8601 instant parsing.
 *
 * Audit finding #9 (design: audit/plan-data-widgets.md §C; evidence: audit/verified.md row
 * B-dto-3) — the backend builds timestamps with Python's `.isoformat()` (e.g.
 * `TransactionItem.executed_at`), which emits a numeric UTC offset (`+00:00`) rather than the
 * `Z` suffix. `java.time.Instant.parse` only accepts the `Z` suffix on Android <= 13 — its
 * libcore predates JDK-8166138, the fix that made `Instant.parse` lenient about numeric offsets
 * on later JDKs. `Instant.parse` alone therefore throws `DateTimeParseException` on real devices
 * for a payload the JVM test runner happily parses.
 *
 * [parseInstantLenient] widens the accepted grammar without pulling in a third-party ISO parser,
 * trying progressively looser formats:
 * 1. [Instant.parse] — the fast path, handles the `Z` suffix.
 * 2. [OffsetDateTime.parse] — handles any explicit numeric offset (`+00:00`, `+02:00`), with or
 *    without fractional seconds.
 * 3. [LocalDateTime.parse] interpreted as UTC — handles a naive timestamp with no offset at all
 *    (defensive; not currently emitted by the backend, but cheap to accept).
 *
 * Anything that fails all three throws the [DateTimeParseException] from the last attempt.
 *
 * Every timestamp parsed from an API/DTO string must go through [parseInstantLenient] (required
 * field) or [parseInstantOrNull] (nullable field) — never call `Instant.parse` directly on
 * data coming off the wire.
 */
fun parseInstantLenient(raw: String): Instant {
    try {
        return Instant.parse(raw)
    } catch (_: DateTimeParseException) {
        // Not a `Z`-suffixed instant — try a numeric offset next.
    }
    try {
        return OffsetDateTime.parse(raw).toInstant()
    } catch (_: DateTimeParseException) {
        // Not an offset date-time either — try a naive local date-time next.
    }
    return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC)
}

/**
 * Null-safe, non-throwing counterpart of [parseInstantLenient] for optional timestamp fields.
 * Returns `null` when [this] is `null` or fails every format [parseInstantLenient] accepts —
 * never throws.
 */
fun String?.parseInstantOrNull(): Instant? {
    val value = this ?: return null
    return try {
        parseInstantLenient(value)
    } catch (_: DateTimeParseException) {
        null
    }
}
