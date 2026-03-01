package com.tradingplatform.app.data.model

import com.tradingplatform.app.data.local.db.entity.AlertEntity
import com.tradingplatform.app.data.local.db.entity.DeviceEntity
import com.tradingplatform.app.data.local.db.entity.PnlSnapshotEntity
import com.tradingplatform.app.data.local.db.entity.PositionEntity
import com.tradingplatform.app.data.local.db.entity.QuoteEntity
import com.tradingplatform.app.domain.model.Alert
import com.tradingplatform.app.domain.model.AlertType
import com.tradingplatform.app.domain.model.AuthTokens
import com.tradingplatform.app.domain.model.Device
import com.tradingplatform.app.domain.model.DeviceStatus
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.model.Transaction
import com.tradingplatform.app.domain.model.User
import java.math.BigDecimal
import java.time.Instant

// ── DTO → Domain ──────────────────────────────────────────────────────────────

fun UserDto.toDomain(): User = User(
    id = id,
    email = email,
    firstName = firstName,
    lastName = lastName,
    isAdmin = isAdmin,
    totpEnabled = totpEnabled,
)

fun TokenResponseDto.toDomain(): AuthTokens = AuthTokens(
    accessToken = accessToken,
    tokenType = tokenType,
    expiresIn = expiresIn,
)

fun PortfolioDto.toDomain(): Portfolio = Portfolio(
    id = id,
    name = name,
    currency = currency,
)

fun PositionDto.toDomain(): Position = Position(
    id = id,
    symbol = symbol,
    quantity = quantity,
    avgPrice = avgPrice,
    currentPrice = currentPrice,
    unrealizedPnl = unrealizedPnl,
    unrealizedPnlPercent = unrealizedPnlPercent,
    status = when (status.lowercase()) {
        "open" -> PositionStatus.OPEN
        "closed" -> PositionStatus.CLOSED
        else -> PositionStatus.OPEN
    },
    openedAt = Instant.parse(openedAt),
)

fun PnlResponseDto.toDomain(): PnlSummary = PnlSummary(
    realizedPnl = realizedPnl,
    unrealizedPnl = unrealizedPnl,
    totalPnl = totalPnl,
    totalPnlPercent = totalPnlPercent,
    tradesCount = tradesCount,
    winningTrades = winningTrades,
    losingTrades = losingTrades,
)

fun NavResponseDto.toDomain(): NavSummary = NavSummary(
    nav = nav,
    cash = cash,
    positionsValue = positionsValue,
    timestamp = Instant.parse(timestamp),
)

fun TransactionDto.toDomain(): Transaction = Transaction(
    id = id,
    symbol = symbol,
    action = action,
    quantity = quantity,
    price = price,
    commission = commission,
    total = total,
    executedAt = Instant.parse(executedAt),
)

fun QuoteDto.toDomain(): Quote = Quote(
    symbol = symbol,
    price = price,
    bid = bid,
    ask = ask,
    volume = volume,
    change = change,
    changePercent = changePercent,
    timestamp = Instant.parse(timestamp),
    source = source,
)

fun DeviceDto.toDomain(): Device = Device(
    id = id,
    name = name,
    status = DeviceStatus.fromApiString(status),
    wgIp = wgIp,
    lastHeartbeat = Instant.parse(lastHeartbeat),
)

// ── Entity → Domain ───────────────────────────────────────────────────────────

fun PositionEntity.toDomain(): Position = Position(
    id = id,
    symbol = symbol,
    quantity = BigDecimal(quantity),
    avgPrice = BigDecimal(avgPrice),
    currentPrice = BigDecimal(currentPrice),
    unrealizedPnl = BigDecimal(unrealizedPnl),
    unrealizedPnlPercent = unrealizedPnlPercent,
    status = when (status) {
        "open" -> PositionStatus.OPEN
        "closed" -> PositionStatus.CLOSED
        else -> PositionStatus.OPEN
    },
    openedAt = Instant.ofEpochMilli(openedAt),
)

fun PnlSnapshotEntity.toDomain(): PnlSummary = PnlSummary(
    realizedPnl = BigDecimal(realizedPnl),
    unrealizedPnl = BigDecimal(unrealizedPnl),
    totalPnl = BigDecimal(totalPnl),
    totalPnlPercent = totalPnlPercent,
    tradesCount = tradesCount,
    winningTrades = winningTrades,
    losingTrades = losingTrades,
)

fun AlertEntity.toDomain(): Alert = Alert(
    id = id,
    title = title,
    body = body,
    type = AlertType.fromString(type),
    receivedAt = Instant.ofEpochMilli(receivedAt),
    read = read,
)

fun DeviceEntity.toDomain(): Device = Device(
    id = id,
    name = name,
    status = DeviceStatus.fromApiString(status),
    wgIp = wgIp,
    lastHeartbeat = Instant.ofEpochMilli(lastHeartbeat),
)

fun QuoteEntity.toDomain(): Quote = Quote(
    symbol = symbol,
    price = BigDecimal(price),
    bid = BigDecimal(bid),
    ask = BigDecimal(ask),
    volume = volume,
    change = BigDecimal(change),
    changePercent = changePercent,
    timestamp = Instant.ofEpochMilli(quoteTimestamp),
    source = source,
)

// ── Domain → Entity ───────────────────────────────────────────────────────────

fun Position.toEntity(syncedAt: Long = System.currentTimeMillis()): PositionEntity = PositionEntity(
    id = id,
    symbol = symbol,
    quantity = quantity.toPlainString(),
    avgPrice = avgPrice.toPlainString(),
    currentPrice = currentPrice.toPlainString(),
    unrealizedPnl = unrealizedPnl.toPlainString(),
    unrealizedPnlPercent = unrealizedPnlPercent,
    status = status.toApiString(),
    openedAt = openedAt.toEpochMilli(),
    syncedAt = syncedAt,
)

fun PnlSummary.toEntity(period: PnlPeriod, syncedAt: Long = System.currentTimeMillis()): PnlSnapshotEntity = PnlSnapshotEntity(
    period = period.toApiString(),
    realizedPnl = realizedPnl.toPlainString(),
    unrealizedPnl = unrealizedPnl.toPlainString(),
    totalPnl = totalPnl.toPlainString(),
    totalPnlPercent = totalPnlPercent,
    tradesCount = tradesCount,
    winningTrades = winningTrades,
    losingTrades = losingTrades,
    syncedAt = syncedAt,
)

fun Alert.toEntity(syncedAt: Long = System.currentTimeMillis()): AlertEntity = AlertEntity(
    id = id,
    title = title,
    body = body,
    type = type.name,
    receivedAt = receivedAt.toEpochMilli(),
    read = read,
    syncedAt = syncedAt,
)

fun Device.toEntity(syncedAt: Long = System.currentTimeMillis()): DeviceEntity = DeviceEntity(
    id = id,
    name = name,
    status = status.name.lowercase(),
    wgIp = wgIp,
    lastHeartbeat = lastHeartbeat.toEpochMilli(),
    syncedAt = syncedAt,
)

fun Quote.toEntity(syncedAt: Long = System.currentTimeMillis()): QuoteEntity = QuoteEntity(
    symbol = symbol,
    price = price.toPlainString(),
    bid = bid.toPlainString(),
    ask = ask.toPlainString(),
    volume = volume,
    change = change.toPlainString(),
    changePercent = changePercent,
    quoteTimestamp = timestamp.toEpochMilli(),
    source = source,
    syncedAt = syncedAt,
)
