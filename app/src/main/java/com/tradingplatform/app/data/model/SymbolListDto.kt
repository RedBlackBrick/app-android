package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.tradingplatform.app.domain.model.SymbolInfo

/**
 * Wire contract for `GET /v1/market-data/symbols` — mirrors `SymbolListItem` /
 * `SymbolListResponse` (trading-platform2 `app/market_data/schemas.py:382-400`,
 * router.py:232-260). Audit finding #6: the previous `MarketDataApi.getSymbols()`
 * declared `Response<List<String>>`, which does not decode the actual paginated
 * `{symbols, total, limit, offset, has_more}` envelope.
 */
@JsonClass(generateAdapter = true)
data class SymbolListItemDto(
    @Json(name = "sid") val sid: Int,
    @Json(name = "ticker") val ticker: String,
    @Json(name = "name") val name: String,
    @Json(name = "exchange") val exchange: String? = null,
    @Json(name = "currency") val currency: String? = null,
    @Json(name = "is_active") val isActive: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class SymbolListResponseDto(
    @Json(name = "symbols") val symbols: List<SymbolListItemDto>,
    @Json(name = "total") val total: Int,
    @Json(name = "limit") val limit: Int,
    @Json(name = "offset") val offset: Int,
    @Json(name = "has_more") val hasMore: Boolean,
)

fun SymbolListItemDto.toDomain(): SymbolInfo = SymbolInfo(
    ticker = ticker,
    name = name,
    exchange = exchange,
    currency = currency,
)
