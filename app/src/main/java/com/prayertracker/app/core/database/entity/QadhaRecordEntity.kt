package com.prayertracker.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.prayertracker.app.core.model.PrayerStatus
import com.prayertracker.app.core.model.SyncStatus

@Entity(
    tableName = "qadha_records",
    foreignKeys = [
        ForeignKey(
            entity = PrayerRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["prayer_record_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["prayer_record_id"], unique = true),
        Index(value = ["qadha_at_epoch"])
    ]
)
data class QadhaRecordEntity(
    @PrimaryKey
    val id: String, // UUID

    @ColumnInfo(name = "prayer_record_id")
    val prayerRecordId: String,

    @ColumnInfo(name = "qadha_status")
    val qadhaStatus: PrayerStatus = PrayerStatus.QADHA_COMPLETED,

    @ColumnInfo(name = "qadha_at_epoch")
    val qadhaAtEpoch: Long, // Epoch millis saat qadha dilakukan

    @ColumnInfo(name = "notes")
    val notes: String? = null,

    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.PENDING_SYNC,

    @ColumnInfo(name = "created_at_epoch")
    val createdAtEpoch: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at_epoch")
    val updatedAtEpoch: Long = System.currentTimeMillis()
)
