package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.OrderDto
import com.tradingplatform.app.data.api.OrdersApi
import com.tradingplatform.app.domain.model.Order
import com.tradingplatform.app.domain.model.OrderSide
import com.tradingplatform.app.domain.model.OrderStatus
import com.tradingplatform.app.domain.model.OrderType
import com.tradingplatform.app.domain.repository.OrdersRepository
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrdersRepositoryImpl @Inject constructor(
    private val api: OrdersApi,
) : OrdersRepository {

    override suspend fun listActiveOrders(portfolioId: String): Result<List<Order>> = runCatching {
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
    ): Result<List<Order>> = runCatching {
        val response = api.listOrderHistory(portfolioId, limit, offset)
        if (!response.isSuccessful) {
            error("List order history failed: HTTP ${response.code()}")
        }
        response.body()?.orders.orEmpty().map(::toDomain)
    }

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

    private fun String?.parseInstantOrNull(): Instant? = this?.let {
        try {
            Instant.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
