package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.Order

interface OrdersRepository {
    suspend fun listActiveOrders(portfolioId: String): Result<List<Order>>

    suspend fun listOrderHistory(
        portfolioId: String,
        limit: Int = 100,
        offset: Int = 0,
    ): Result<List<Order>>
}
