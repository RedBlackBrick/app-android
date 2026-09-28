package com.tradingplatform.app.domain.usecase.orders

import com.tradingplatform.app.domain.model.Order
import com.tradingplatform.app.domain.model.Page
import com.tradingplatform.app.domain.repository.OrdersRepository
import javax.inject.Inject

/**
 * List the user's terminal orders for the given portfolio, one page at a time.
 * Backend filters to FILLED / CANCELLED / REJECTED / EXPIRED.
 *
 * [Page.total] carries the backend `count` so callers can derive `hasMore`
 * for a "Charger plus" button without guessing from page fullness.
 */
class GetOrderHistoryUseCase @Inject constructor(
    private val repository: OrdersRepository,
) {
    suspend operator fun invoke(
        portfolioId: String,
        limit: Int = 50,
        offset: Int = 0,
    ): Result<Page<Order>> = repository.listOrderHistory(portfolioId, limit, offset)
}
