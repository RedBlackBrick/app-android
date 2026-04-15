package com.tradingplatform.app.data.model

import com.tradingplatform.app.data.local.db.entity.AlertEntity
import com.tradingplatform.app.data.local.db.entity.DeviceEntity
import com.tradingplatform.app.data.local.db.entity.PnlSnapshotEntity
import com.tradingplatform.app.data.local.db.entity.PositionEntity
import com.tradingplatform.app.data.local.db.entity.QuoteEntity
import com.tradingplatform.app.domain.model.Alert
import com.tradingplatform.app.domain.model.AlertType
import com.tradingplatform.app.domain.model.AuthTokens
import com.tradingplatform.app.domain.model.BrokerGatewayStatus
import com.tradingplatform.app.domain.model.BrokerSummary
import com.tradingplatform.app.domain.model.Device
import com.tradingplatform.app.domain.model.DeviceStatus
import com.tradingplatform.app.domain.model.ScraperCircuitState
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PerformanceMetrics
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.model.Transaction
import com.tradingplatform.app.domain.model.User
import com.tradingplatform.app.domain.model.VpnPeer
import com.tradingplatform.app.domain.model.VpnPeerType
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
    brokerSummary = brokerSummary?.let {
        BrokerSummary(
            brokerCode = it.brokerCode,
            connectionStatus = it.connectionStatus,
            executionMode = it.executionMode,
            deviceId = it.deviceId,
        )
    },
)

fun PositionDto.toDomain(): Position = Position(
    id = id,
    symbol = symbol,
    quantity = quantity,
    avgPrice = avgPrice,
    currentPrice = currentPrice,
    unrealizedPnl = unrealizedPnl,
    unrealizedPnlPercent = unrealizedPnlPercent,
    status = if (isActive) PositionStatus.OPEN else PositionStatus.CLOSED,
    openedAt = openedAt?.let { Instant.parse(it) },
)

fun PerformanceResponseDto.toPerformanceMetrics(): PerformanceMetrics = PerformanceMetrics(
    totalReturn = totalReturn,
    totalReturnPct = totalReturnPct,
    sharpeRatio = sharpeRatio,
    sortinoRatio = sortinoRatio,
    maxDrawdown = maxDrawdown,
    volatility = volatility,
    cagr = cagr,
    winRate = winRate,
    profitFactor = profitFactor,
    avgTradeReturn = avgTradeReturn,
)

fun PerformanceResponseDto.toDomain(): PnlSummary = PnlSummary(
    totalReturn = totalReturn,
    totalReturnPct = totalReturnPct,
    sharpeRatio = sharpeRatio,
    sortinoRatio = sortinoRatio,
    maxDrawdown = maxDrawdown,
    volatility = volatility,
    cagr = cagr,
    winRate = winRate,
    profitFactor = profitFactor,
    avgTradeReturn = avgTradeReturn,
)

fun PnlResponseDto.toPnlSummary(): PnlSummary = PnlSummary(
    totalReturn = totalPnl,
    totalReturnPct = totalPnlPercent / 100.0,
    sharpeRatio = null,
    sortinoRatio = null,
    maxDrawdown = null,
    volatility = null,
    cagr = null,
    winRate = if (tradesCount > 0) winningTrades.toDouble() / tradesCount else null,
    profitFactor = null,
    avgTradeReturn = null,
    tradesCount = tradesCount,
    winningTrades = winningTrades,
    losingTrades = losingTrades,
)

fun PortfolioDetailDto.toDomain(): NavSummary = NavSummary(
    currentValue = portfolio.currentValue,
    cashBalance = portfolio.cashBalance,
    totalRealizedPnl = totalRealizedPnl,
    totalUnrealizedPnl = totalUnrealizedPnl,
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
    sourceName = sourceName,
    sourceType = sourceType,
    quality = quality,
    dataMode = dataMode,
)

fun DeviceDto.toDomain(): Device = Device(
    id = id,
    name = name,
    status = DeviceStatus.fromApiString(status),
    wgIp = wgIp,
    lastHeartbeat = lastHeartbeat?.let { Instant.parse(it) },
    cpuPct = cpuPct,
    memoryPct = memoryPct,
    temperature = temperature,
    diskPct = diskPct,
    uptimeSeconds = uptimeSeconds,
    firmwareVersion = firmwareVersion,
    hostname = hostname,
    brokerGateway = if (brokerGatewayEnabled != null || brokerGatewayStatus != null) {
        BrokerGatewayStatus(
            enabled = brokerGatewayEnabled ?: false,
            status = brokerGatewayStatus ?: "stopped",
            brokerId = brokerGatewayBrokerId,
        )
    } else null,
    lastTicksSent = lastTicksSent,
    lastScraperErrors = lastScraperErrors,
    scrapersCircuit = scrapersCircuit?.mapValues { (_, v) ->
        ScraperCircuitState(
            state = v.state,
            consecutiveFailures = v.consecutiveFailures,
            totalTrips = v.totalTrips,
        )
    },
    availableMemoryMb = availableMemoryMb,
)

fun VpnPeerDto.toDomain(): VpnPeer = VpnPeer(
    id = id,
    userId = userId,
    label = label,
    peerType = when (peerType.uppercase()) {
        "ANDROID_APP" -> VpnPeerType.ANDROID_APP
        "RADXA_BOARD" -> VpnPeerType.RADXA_BOARD
        else -> VpnPeerType.WEB_CLIENT
    },
    wgTunnelIp = wgTunnelIp,
    isActive = isActive,
    pairedAt = Instant.parse(pairedAt),
    lastHandshake = lastHandshake?.let { Instant.parse(it) },
)

// ── Entity → Domain ───────────────────────────────────────────────────────────

fun PositionEntity.toDomain(): Position = Position(
    id = id,
    symbol = symbol,
    quantity = BigDecimal(quantity),
    avgPrice = BigDecimal(avgPrice),
    currentPrice = currentPrice?.let { BigDecimal(it) },
    unrealizedPnl = unrealizedPnl?.let { BigDecimal(it) },
    unrealizedPnlPercent = unrealizedPnlPercent,
    status = when (status) {
        "open" -> PositionStatus.OPEN
        "closed" -> PositionStatus.CLOSED
        else -> PositionStatus.OPEN
    },
    openedAt = openedAt?.let { Instant.ofEpochMilli(it) },
)

fun PnlSnapshotEntity.toDomain(): PnlSummary = PnlSummary(
    totalReturn = totalReturn?.let { BigDecimal(it) },
    totalReturnPct = totalReturnPct,
    sharpeRatio = sharpeRatio,
    sortinoRatio = sortinoRatio,
    maxDrawdown = maxDrawdown,
    volatility = volatility,
    cagr = cagr,
    winRate = winRate,
    profitFactor = profitFactor,
    avgTradeReturn = avgTradeReturn?.let { BigDecimal(it) },
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
    lastHeartbeat = lastHeartbeat?.let { Instant.ofEpochMilli(it) },
    cpuPct = cpuPct,
    memoryPct = memoryPct,
    temperature = temperature,
    diskPct = diskPct,
    uptimeSeconds = uptimeSeconds,
    firmwareVersion = firmwareVersion,
    hostname = hostname,
    brokerGateway = if (brokerGatewayEnabled != null || brokerGatewayStatus != null) {
        BrokerGatewayStatus(
            enabled = brokerGatewayEnabled ?: false,
            status = brokerGatewayStatus ?: "stopped",
            brokerId = brokerGatewayBrokerId,
        )
    } else null,
    lastTicksSent = null,
    lastScraperErrors = null,
    scrapersCircuit = null,
    availableMemoryMb = availableMemoryMb,
)

fun QuoteEntity.toDomain(): Quote = Quote(
    symbol = symbol,
    price = BigDecimal(price),
    bid = bid?.let { BigDecimal(it) },
    ask = ask?.let { BigDecimal(it) },
    volume = volume,
    change = BigDecimal(change),
    changePercent = changePercent,
    timestamp = Instant.ofEpochMilli(quoteTimestamp),
    source = source,
    sourceName = sourceName,
    sourceType = sourceType,
    quality = quality,
    dataMode = dataMode,
)

// ── Domain → Entity ───────────────────────────────────────────────────────────

fun Position.toEntity(syncedAt: Long = System.currentTimeMillis()): PositionEntity = PositionEntity(
    id = id,
    symbol = symbol,
    quantity = quantity.toPlainString(),
    avgPrice = avgPrice.toPlainString(),
    currentPrice = currentPrice?.toPlainString(),
    unrealizedPnl = unrealizedPnl?.toPlainString(),
    unrealizedPnlPercent = unrealizedPnlPercent,
    status = status.toApiString(),
    openedAt = openedAt?.toEpochMilli(),
    syncedAt = syncedAt,
)

fun PnlSummary.toEntity(period: PnlPeriod, syncedAt: Long = System.currentTimeMillis()): PnlSnapshotEntity = PnlSnapshotEntity(
    period = period.toApiString(),
    totalReturn = totalReturn?.toPlainString(),
    totalReturnPct = totalReturnPct,
    sharpeRatio = sharpeRatio,
    sortinoRatio = sortinoRatio,
    maxDrawdown = maxDrawdown,
    volatility = volatility,
    cagr = cagr,
    winRate = winRate,
    profitFactor = profitFactor,
    avgTradeReturn = avgTradeReturn?.toPlainString(),
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
    lastHeartbeat = lastHeartbeat?.toEpochMilli(),
    syncedAt = syncedAt,
    cpuPct = cpuPct,
    memoryPct = memoryPct,
    temperature = temperature,
    diskPct = diskPct,
    uptimeSeconds = uptimeSeconds,
    firmwareVersion = firmwareVersion,
    hostname = hostname,
    brokerGatewayEnabled = brokerGateway?.enabled,
    brokerGatewayStatus = brokerGateway?.status,
    brokerGatewayBrokerId = brokerGateway?.brokerId,
    availableMemoryMb = availableMemoryMb,
)

fun Quote.toEntity(syncedAt: Long = System.currentTimeMillis()): QuoteEntity = QuoteEntity(
    symbol = symbol,
    price = price.toPlainString(),
    bid = bid?.toPlainString(),
    ask = ask?.toPlainString(),
    volume = volume,
    change = change.toPlainString(),
    changePercent = changePercent,
    quoteTimestamp = timestamp.toEpochMilli(),
    source = source,
    sourceName = sourceName,
    sourceType = sourceType,
    quality = quality,
    dataMode = dataMode,
    syncedAt = syncedAt,
)
