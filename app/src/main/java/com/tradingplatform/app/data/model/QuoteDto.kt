package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigDecimal

@JsonClass(generateAdapter = true)
data class QuoteDto(
    @Json(name = "symbol") val symbol: String,
    @Json(name = "price") val price: BigDecimal,
    @Json(name = "bid") val bid: BigDecimal? = null,
    @Json(name = "ask") val ask: BigDecimal? = null,
    // Nullable mirroring the Pydantic `QuoteResponse` on the server. These
    // fields can legitimately be `null` (e.g. an index with no volume, a
    // freshly-opened market with no change yet) and a non-null Kotlin type
    // would crash Moshi at parse time.
    @Json(name = "volume") val volume: Long? = null,
    @Json(name = "change") val change: BigDecimal? = null,
    @Json(name = "change_percent") val changePercent: Double? = null,
    @Json(name = "timestamp") val timestamp: String,
    @Json(name = "source") val source: String,
    @Json(name = "source_name") val sourceName: String? = null,
    @Json(name = "source_type") val sourceType: String? = null,
    @Json(name = "quality") val quality: Int? = null,
    @Json(name = "data_mode") val dataMode: String? = null,
)
