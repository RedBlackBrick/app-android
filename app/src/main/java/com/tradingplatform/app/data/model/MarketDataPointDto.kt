package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigDecimal

/**
 * Single OHLCV point — mirrors `MarketDataPoint` (trading-platform2
 * `app/market_data/schemas.py:28-83`). `open`/`high`/`low`/`close` are required
 * (no default) on the backend, so they are decoded as non-null here; `volume`
 * is `int | None = None`. Pydantic serializes `Decimal` as a JSON string.
 */
@JsonClass(generateAdapter = true)
data class MarketDataPointDto(
    @Json(name = "timestamp") val timestamp: String,
    @Json(name = "open") val open: BigDecimal,
    @Json(name = "high") val high: BigDecimal,
    @Json(name = "low") val low: BigDecimal,
    @Json(name = "close") val close: BigDecimal,
    @Json(name = "volume") val volume: Long? = null,
)
