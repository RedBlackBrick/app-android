package com.tradingplatform.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for caching portfolio performance snapshots.
 *
 * Sourced from `GET /v1/portfolios/{id}/performance` (backend `PerformanceMetrics` schema).
 * All fields except [period] and [syncedAt] are nullable — the backend may not have
 * enough data to compute them (e.g. brand-new portfolio with no trades).
 */
@Entity(tableName = "pnl_snapshots")
data class PnlSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "period") val period: String,
    @ColumnInfo(name = "total_return") val totalReturn: String?,
    @ColumnInfo(name = "total_return_pct") val totalReturnPct: Double?,
    @ColumnInfo(name = "sharpe_ratio") val sharpeRatio: Double?,
    @ColumnInfo(name = "sortino_ratio") val sortinoRatio: Double?,
    @ColumnInfo(name = "max_drawdown") val maxDrawdown: Double?,
    @ColumnInfo(name = "volatility") val volatility: Double?,
    @ColumnInfo(name = "cagr") val cagr: Double?,
    @ColumnInfo(name = "win_rate") val winRate: Double?,
    @ColumnInfo(name = "profit_factor") val profitFactor: Double?,
    @ColumnInfo(name = "avg_trade_return") val avgTradeReturn: String?,
    @ColumnInfo(name = "synced_at") val syncedAt: Long,
)
