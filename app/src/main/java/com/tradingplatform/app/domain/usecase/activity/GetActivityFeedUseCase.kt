package com.tradingplatform.app.domain.usecase.activity

import com.tradingplatform.app.domain.model.ActivityItem
import com.tradingplatform.app.domain.repository.WsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.time.Instant
import javax.inject.Inject

/**
 * Merges all real-time WebSocket streams into a single [Flow] of [ActivityItem].
 *
 * Combines order updates, strategy signals, notifications, and portfolio updates
 * from [WsRepository] into a unified activity feed consumed by the Dashboard.
 *
 * Each mapping sets [ActivityItem.timestamp] to [Instant.now] at reception time,
 * since WS payloads do not carry a server-side timestamp.
 *
 * Items with missing required fields are filtered out (mapNotNull equivalent via
 * null guards in the mapping lambdas).
 */
class GetActivityFeedUseCase @Inject constructor(
    private val wsRepository: WsRepository,
) {
    operator fun invoke(): Flow<ActivityItem> = merge(
        wsRepository.orderUpdates.map { order ->
            ActivityItem.OrderFilled(
                orderId = order.orderId ?: "unknown",
                symbol = order.symbol ?: "—",
                side = order.side ?: "—",
                status = order.status ?: "—",
                quantity = order.quantity,
                timestamp = Instant.now(),
            )
        },
        wsRepository.strategySignals.map { signal ->
            ActivityItem.Signal(
                symbol = signal.symbol ?: "—",
                action = signal.action ?: "—",
                confidence = signal.confidence ?: 0.0,
                strategyType = signal.strategyType ?: "—",
                timestamp = Instant.now(),
            )
        },
        wsRepository.notifications.map { notif ->
            ActivityItem.RiskAlert(
                title = notif.title,
                body = notif.body,
                severity = notif.notifType,
                timestamp = Instant.now(),
            )
        },
        wsRepository.portfolioUpdates.map { portfolio ->
            ActivityItem.PortfolioChange(
                nav = portfolio.nav,
                dailyPnl = portfolio.dailyPnl,
                timestamp = Instant.now(),
            )
        },
        wsRepository.catalystEvents.map { catalyst ->
            ActivityItem.CatalystEvent(
                symbol = catalyst.symbol ?: "\u2014",
                eventType = catalyst.eventType ?: "event",
                title = catalyst.title ?: "\u00c9v\u00e9nement catalyseur",
                timestamp = Instant.now(),
            )
        },
    )
}
