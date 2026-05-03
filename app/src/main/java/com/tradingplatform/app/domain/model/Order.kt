package com.tradingplatform.app.domain.model

import java.math.BigDecimal
import java.time.Instant

/**
 * Single order on the user's portfolio. Mirrors the backend ``OrderResponse``
 * schema (subset).
 *
 * Pure Kotlin domain model, no Android dependencies.
 */
data class Order(
    val id: Long,
    val symbol: String,
    val side: OrderSide,
    val quantity: BigDecimal?,
    val orderType: OrderType,
    val status: OrderStatus?,
    val filledQuantity: BigDecimal,
    val averageFillPrice: BigDecimal?,
    val limitPrice: BigDecimal?,
    val stopPrice: BigDecimal?,
    val portfolioId: String,
    val brokerOrderId: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

enum class OrderSide {
    BUY,
    SELL,
    ;

    companion object {
        fun fromWire(value: String?): OrderSide = when (value?.uppercase()) {
            "SELL" -> SELL
            else -> BUY
        }
    }
}

enum class OrderType {
    MARKET,
    LIMIT,
    STOP,
    STOP_LIMIT,
    TRAILING_STOP,
    LOC,
    UNKNOWN,
    ;

    companion object {
        fun fromWire(value: String?): OrderType = when (value?.uppercase()) {
            "MARKET" -> MARKET
            "LIMIT" -> LIMIT
            "STOP" -> STOP
            "STOP_LIMIT" -> STOP_LIMIT
            "TRAILING_STOP" -> TRAILING_STOP
            "LOC" -> LOC
            else -> UNKNOWN
        }
    }
}

/**
 * Order status — superset that covers both active and terminal states the
 * backend may return. ``UNKNOWN`` is the safe fallback when the wire value
 * does not match a known constant (forward compatibility with new statuses).
 */
enum class OrderStatus {
    PENDING_APPROVAL,
    PENDING,
    SUBMITTED,
    PARTIAL,
    FILLED,
    CANCELLED,
    REJECTED,
    EXPIRED,
    ERROR,
    PENDING_CANCEL,
    ROLLOVER_PENDING,
    PENDING_RETRY,
    UNKNOWN,
    ;

    val isTerminal: Boolean
        get() = this == FILLED || this == CANCELLED || this == REJECTED ||
            this == EXPIRED || this == ERROR

    companion object {
        fun fromWire(value: String?): OrderStatus = when (value?.uppercase()) {
            "PENDING_APPROVAL" -> PENDING_APPROVAL
            "PENDING" -> PENDING
            "SUBMITTED" -> SUBMITTED
            "PARTIAL" -> PARTIAL
            "FILLED" -> FILLED
            "CANCELLED", "CANCELED" -> CANCELLED
            "REJECTED" -> REJECTED
            "EXPIRED" -> EXPIRED
            "ERROR" -> ERROR
            "PENDING_CANCEL" -> PENDING_CANCEL
            "ROLLOVER_PENDING" -> ROLLOVER_PENDING
            "PENDING_RETRY" -> PENDING_RETRY
            else -> UNKNOWN
        }
    }
}
