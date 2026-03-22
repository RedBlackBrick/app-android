package com.tradingplatform.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val symbol: String,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis(),
)
