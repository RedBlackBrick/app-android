package com.tradingplatform.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity caching the P&L summary of one period — **one row per period**.
 *
 * Sourced from `GET /v1/portfolios/{id}/pnl?period=…` (backend `PnlResponse` schema),
 * written only by `PortfolioRepositoryImpl.getPnlSummary` (Dashboard and
 * `WidgetUpdateWorker` both go through `GetPnlUseCase`). Read by `PnlWidget`.
 *
 * [period] is the primary key, so `OnConflictStrategy.REPLACE` is a true upsert.
 * Amounts are stored as BigDecimal plain strings (TEXT). [totalPnlPercent] is stored
 * as a **fraction** (backend sends a percent — converted in the mapper), consistent
 * with the domain convention (fractions everywhere).
 */
@Entity(
    tableName = "pnl_snapshots",
    indices = [Index(value = ["synced_at"])]
)
data class PnlSnapshotEntity(
    @PrimaryKey @ColumnInfo(name = "period") val period: String,
    @ColumnInfo(name = "realized_pnl") val realizedPnl: String,
    @ColumnInfo(name = "unrealized_pnl") val unrealizedPnl: String,
    @ColumnInfo(name = "total_pnl") val totalPnl: String,
    @ColumnInfo(name = "total_pnl_percent") val totalPnlPercent: Double,
    @ColumnInfo(name = "trades_count") val tradesCount: Int,
    @ColumnInfo(name = "winning_trades") val winningTrades: Int,
    @ColumnInfo(name = "losing_trades") val losingTrades: Int,
    @ColumnInfo(name = "synced_at") val syncedAt: Long,
)
