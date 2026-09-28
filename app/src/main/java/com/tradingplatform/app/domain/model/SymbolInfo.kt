package com.tradingplatform.app.domain.model

/**
 * Symbol catalogue entry — mirrors `SymbolListItem` (trading-platform2
 * `app/market_data/schemas.py:382-391`), minus `sid`/`is_active` which are
 * data-layer concerns (filtering happens in the repository).
 */
data class SymbolInfo(
    val ticker: String,
    val name: String,
    val exchange: String? = null,
    val currency: String? = null,
)

/**
 * One offset-paginated page of the symbols catalogue.
 *
 * @param nextOffset offset to request for the following page (`offset + returned count`).
 */
data class SymbolPage(
    val items: List<SymbolInfo>,
    val hasMore: Boolean,
    val nextOffset: Int,
)
