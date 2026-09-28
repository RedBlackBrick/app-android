package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigDecimal

/**
 * PR-5c FINDING / PR-2.5 fix: `PositionResponse` (app/portfolio/schemas.py:980-981)
 * declares `quantity`/`average_price` as `Optional[str]` — the backend can legally
 * omit or null them (e.g. a freshly-created row before the DB `NOT NULL DEFAULT 0`
 * column is backfilled by the builder). They are therefore nullable here too;
 * [com.tradingplatform.app.data.model.toDomain] maps a null to `BigDecimal.ZERO`
 * to keep the non-nullable domain [com.tradingplatform.app.domain.model.Position]
 * contract without risking a Moshi decode crash.
 */
@JsonClass(generateAdapter = true)
data class PositionDto(
    @Json(name = "id") val id: Int,
    @Json(name = "symbol") val symbol: String,
    @Json(name = "quantity") val quantity: BigDecimal? = null,
    @Json(name = "average_price") val avgPrice: BigDecimal? = null,
    @Json(name = "last_price") val currentPrice: BigDecimal? = null,
    @Json(name = "unrealized_pnl") val unrealizedPnl: BigDecimal? = null,
    @Json(name = "unrealized_pnl_pct") val unrealizedPnlPercent: Double? = null,
    @Json(name = "is_active") val isActive: Boolean = true,
    @Json(name = "entry_timestamp") val openedAt: String? = null,
)
