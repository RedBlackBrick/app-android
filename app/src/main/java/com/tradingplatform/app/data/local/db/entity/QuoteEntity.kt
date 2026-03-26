package com.tradingplatform.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "quotes",
    indices = [Index(value = ["synced_at"])]
)
data class QuoteEntity(
    @PrimaryKey val symbol: String,
    @ColumnInfo(name = "price") val price: String,
    @ColumnInfo(name = "bid") val bid: String?,
    @ColumnInfo(name = "ask") val ask: String?,
    @ColumnInfo(name = "volume") val volume: Long,
    @ColumnInfo(name = "change") val change: String,
    @ColumnInfo(name = "change_percent") val changePercent: Double,
    @ColumnInfo(name = "quote_timestamp") val quoteTimestamp: Long,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "synced_at") val syncedAt: Long,
)
