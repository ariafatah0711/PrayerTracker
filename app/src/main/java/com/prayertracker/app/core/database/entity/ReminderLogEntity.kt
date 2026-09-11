package com.prayertracker.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.prayertracker.app.core.model.ReminderType
import com.prayertracker.app.core.model.SyncStatus
import com.prayertracker.app.core.model.UserResponse

@Entity(
    tableName = "reminder_logs",
    foreignKeys = [
        ForeignKey(
            entity = PrayerRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["prayer_record_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["prayer_record_id"]),
        Index(value = ["triggered_at_epoch"])
    ]
)
data class ReminderLogEntity(
    @PrimaryKey
    val id: String, // UUID

    @ColumnInfo(name = "prayer_record_id")
    val prayerRecordId: String,

    @ColumnInfo(name = "reminder_type")
    val reminderType: ReminderType,

    @ColumnInfo(name = "triggered_at_epoch")
    val triggeredAtEpoch: Long,

    @ColumnInfo(name = "user_response")
    val userResponse: UserResponse? = null,

    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.PENDING_SYNC,

    @ColumnInfo(name = "created_at_epoch")
    val createdAtEpoch: Long = System.currentTimeMillis()
)
