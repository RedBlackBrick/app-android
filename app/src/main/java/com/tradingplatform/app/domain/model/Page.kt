package com.tradingplatform.app.domain.model

/**
 * A single page of a paginated list result.
 *
 * [total] is the total number of items available server-side (not just this
 * page's size) — used by callers to derive `hasMore` without guessing from
 * whether the page came back full.
 */
data class Page<T>(
    val items: List<T>,
    val total: Int,
)
