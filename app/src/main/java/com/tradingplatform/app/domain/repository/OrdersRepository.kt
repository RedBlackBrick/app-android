package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.Order
import com.tradingplatform.app.domain.model.Page

interface OrdersRepository {
    suspend fun listActiveOrders(portfolioId: String): Result<List<Order>>

    /**
     * Paginated terminal order history. [Page.total] carries the backend
     * `count` field so callers can derive `hasMore` without guessing from
     * whether the page came back full.
     */
    suspend fun listOrderHistory(
        portfolioId: String,
        limit: Int = 100,
        offset: Int = 0,
    ): Result<Page<Order>>
}
