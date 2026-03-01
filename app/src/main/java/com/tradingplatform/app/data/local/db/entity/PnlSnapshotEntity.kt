package com.tradingplatform.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pnl_snapshots")
data class PnlSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "period") val period: String,
    @ColumnInfo(name = "realized_pnl") val realizedPnl: String,
    @ColumnInfo(name = "unrealized_pnl") val unrealizedPnl: String,
    @ColumnInfo(name = "total_pnl") val totalPnl: String,
    @ColumnInfo(name = "total_pnl_percent") val totalPnlPercent: Double,
    @ColumnInfo(name = "trades_count") val tradesCount: Int,
    @ColumnInfo(name = "winning_trades") val winningTrades: Int,
    @ColumnInfo(name = "losing_trades") val losingTrades: Int,
    @ColumnInfo(name = "synced_at") val syncedAt: Long,
)
