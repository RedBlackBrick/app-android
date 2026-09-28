package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Wire contract shared by `GET /v1/market-data/` (`get_latest`, DESC-ordered) and
 * `GET /v1/market-data/{symbol}/history` (`get_range`, ascending/chronological) —
 * mirrors `MarketDataResponse` (trading-platform2 `app/market_data/schemas.py:86-94`).
 * Audit finding #7: the previous `MarketDataApi.getHistory()` declared
 * `Response<List<MarketDataPointDto>>`, which does not decode the actual
 * `{symbol, data, count, timeframe, next_cursor}` envelope.
 */
@JsonClass(generateAdapter = true)
data class MarketDataResponseDto(
    @Json(name = "symbol") val symbol: String,
    @Json(name = "data") val data: List<MarketDataPointDto>,
    @Json(name = "count") val count: Int,
    @Json(name = "timeframe") val timeframe: String? = null,
    @Json(name = "next_cursor") val nextCursor: String? = null,
)
