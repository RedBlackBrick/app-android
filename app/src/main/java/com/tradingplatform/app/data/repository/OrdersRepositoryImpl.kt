package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.OrderDto
import com.tradingplatform.app.data.api.OrdersApi
import com.tradingplatform.app.data.api.OrdersWriteApi
import com.tradingplatform.app.domain.model.Order
import com.tradingplatform.app.domain.model.OrderSide
import com.tradingplatform.app.domain.model.OrderStatus
import com.tradingplatform.app.domain.model.OrderType
import com.tradingplatform.app.domain.model.Page
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.repository.OrdersRepository
import com.tradingplatform.app.domain.util.parseInstantOrNull
import com.tradingplatform.app.domain.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrdersRepositoryImpl @Inject constructor(
    private val api: OrdersApi,
    private val writeApi: OrdersWriteApi,
) : OrdersRepository {

    override suspend fun listActiveOrders(portfolioId: String): Result<List<Order>> = runCatchingCancellable {
        val response = api.listActiveOrders(portfolioId)
        if (!response.isSuccessful) {
            error("List active orders failed: HTTP ${response.code()}")
        }
        response.body()?.orders.orEmpty().map(::toDomain)
    }

    override suspend fun listOrderHistory(
        portfolioId: String,
        limit: Int,
        offset: Int,
    ): Result<Page<Order>> = runCatchingCancellable {
        val response = api.listOrderHistory(portfolioId, limit, offset)
        if (!response.isSuccessful) {
            error("List order history failed: HTTP ${response.code()}")
        }
        val body = response.body()
        Page(
            items = body?.orders.orEmpty().map(::toDomain),
            total = body?.count ?: 0,
        )
    }

    override suspend fun cancelOrder(orderId: Long): Result<WriteOutcome> =
        OrdersStrategiesWriteSupport.execute(
            endpoint = CANCEL_ENDPOINT,
            conflictMessage = "Ordre non annulable dans son état actuel",
        ) { writeApi.cancelOrder(orderId) }

    private fun toDomain(dto: OrderDto): Order = Order(
        id = dto.id,
        symbol = dto.symbol,
        side = OrderSide.fromWire(dto.side),
        quantity = dto.quantity,
        orderType = OrderType.fromWire(dto.orderType),
        status = dto.status?.let(OrderStatus::fromWire),
        filledQuantity = dto.filledQuantity,
        averageFillPrice = dto.averageFillPrice,
        limitPrice = dto.limitPrice,
        stopPrice = dto.stopPrice,
        portfolioId = dto.portfolioId,
        brokerOrderId = dto.brokerOrderId,
        createdAt = dto.createdAt.parseInstantOrNull(),
        updatedAt = dto.updatedAt.parseInstantOrNull(),
    )

    private companion object {
        // Gabarit (jamais l'identifiant réel) : utilisé dans HttpStatusException et les logs.
        const val CANCEL_ENDPOINT = "v1/orders/{order_id}/cancel"
    }
}
