package com.tradingplatform.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "devices",
    indices = [Index(value = ["synced_at"])]
)
data class DeviceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "wg_ip") val wgIp: String,
    @ColumnInfo(name = "last_heartbeat") val lastHeartbeat: Long?,
    @ColumnInfo(name = "synced_at") val syncedAt: Long,
    @ColumnInfo(name = "cpu_pct") val cpuPct: Float? = null,
    @ColumnInfo(name = "memory_pct") val memoryPct: Float? = null,
    @ColumnInfo(name = "temperature") val temperature: Float? = null,
    @ColumnInfo(name = "disk_pct") val diskPct: Float? = null,
    @ColumnInfo(name = "uptime_seconds") val uptimeSeconds: Long? = null,
    @ColumnInfo(name = "firmware_version") val firmwareVersion: String? = null,
    @ColumnInfo(name = "hostname") val hostname: String? = null,
)
