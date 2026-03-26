package com.tradingplatform.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "positions",
    indices = [Index(value = ["synced_at"])]
)
data class PositionEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "symbol") val symbol: String,
    @ColumnInfo(name = "quantity") val quantity: String,        // BigDecimal sérialisé en String
    @ColumnInfo(name = "avg_price") val avgPrice: String,
    @ColumnInfo(name = "current_price") val currentPrice: String?,
    @ColumnInfo(name = "unrealized_pnl") val unrealizedPnl: String?,
    @ColumnInfo(name = "unrealized_pnl_percent") val unrealizedPnlPercent: Double?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "opened_at") val openedAt: Long?,        // Instant → epoch millis
    @ColumnInfo(name = "synced_at") val syncedAt: Long,
)
