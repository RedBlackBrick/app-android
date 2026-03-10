package com.tradingplatform.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "wg_ip") val wgIp: String,
    @ColumnInfo(name = "last_heartbeat") val lastHeartbeat: Long?,
    @ColumnInfo(name = "synced_at") val syncedAt: Long,
)
