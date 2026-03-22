package com.tradingplatform.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "alerts",
    indices = [
        Index(value = ["synced_at"]),
        Index(value = ["received_at"]),
    ]
)
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,    // epoch millis
    @ColumnInfo(name = "read") val read: Boolean,
    @ColumnInfo(name = "synced_at") val syncedAt: Long,  // epoch millis — timestamp de dernière sync
)
