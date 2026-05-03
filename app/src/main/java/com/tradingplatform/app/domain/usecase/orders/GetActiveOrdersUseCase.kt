package com.tradingplatform.app.domain.usecase.orders

import com.tradingplatform.app.domain.model.Order
import com.tradingplatform.app.domain.repository.OrdersRepository
import javax.inject.Inject

/**
 * List the user's active (non-terminal) orders for the given portfolio.
 * Backend filters to PENDING / SUBMITTED / PARTIAL.
 */
class GetActiveOrdersUseCase @Inject constructor(
    private val repository: OrdersRepository,
) {
    suspend operator fun invoke(portfolioId: String): Result<List<Order>> =
        repository.listActiveOrders(portfolioId)
}
