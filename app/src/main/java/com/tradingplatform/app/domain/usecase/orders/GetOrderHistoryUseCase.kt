package com.tradingplatform.app.domain.usecase.orders

import com.tradingplatform.app.domain.model.Order
import com.tradingplatform.app.domain.repository.OrdersRepository
import javax.inject.Inject

/**
 * List the user's terminal orders for the given portfolio.
 * Backend filters to FILLED / CANCELLED / REJECTED / EXPIRED.
 *
 * The default page size matches the screen's typical first-load needs;
 * the use case stays paginated so a future "Charger plus" button can be
 * wired without changing the contract.
 */
class GetOrderHistoryUseCase @Inject constructor(
    private val repository: OrdersRepository,
) {
    suspend operator fun invoke(
        portfolioId: String,
        limit: Int = 50,
        offset: Int = 0,
    ): Result<List<Order>> = repository.listOrderHistory(portfolioId, limit, offset)
}
