package com.prayertracker.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.prayertracker.app.core.model.PrayerName
import com.prayertracker.app.core.model.PrayerStatus
import com.prayertracker.app.core.model.SyncStatus

@Entity(
    tableName = "prayer_records",
    indices = [
        Index(value = ["prayer_date", "prayer_name"], unique = true),
        Index(value = ["status"]),
        Index(value = ["scheduled_time_epoch"]),
        Index(value = ["sync_status"])
    ]
)
data class PrayerRecordEntity(
    @PrimaryKey
    val id: String, // UUID

    @ColumnInfo(name = "user_id")
    val userId: String = "local_user",

    @ColumnInfo(name = "prayer_name")
    val prayerName: PrayerName,

    @ColumnInfo(name = "prayer_date")
    val prayerDate: String, // YYYY-MM-DD

    @ColumnInfo(name = "scheduled_time_epoch")
    val scheduledTimeEpoch: Long, // Epoch millis

    @ColumnInfo(name = "end_time_epoch")
    val endTimeEpoch: Long, // Epoch millis batas akhir waktu salat

    @ColumnInfo(name = "status")
    val status: PrayerStatus,

    @ColumnInfo(name = "completed_at_epoch")
    val completedAtEpoch: Long? = null,

    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.PENDING_SYNC,

    @ColumnInfo(name = "created_at_epoch")
    val createdAtEpoch: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at_epoch")
    val updatedAtEpoch: Long = System.currentTimeMillis()
)
