package com.prayertracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.prayertracker.app.core.database.entity.ReminderLogEntity

@Dao
interface ReminderLogDao {

    @Insert
    suspend fun insert(log: ReminderLogEntity): Long

    @Query("SELECT * FROM reminder_logs WHERE prayer_record_id = :prayerRecordId ORDER BY triggered_at_epoch ASC")
    suspend fun getLogsForPrayer(prayerRecordId: String): List<ReminderLogEntity>

    @Query("DELETE FROM reminder_logs")
    suspend fun clearAll()
}
