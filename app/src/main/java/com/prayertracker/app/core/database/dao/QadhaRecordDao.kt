package com.prayertracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.prayertracker.app.core.database.entity.QadhaRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QadhaRecordDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(qadhaRecord: QadhaRecordEntity): Long

    @Query("SELECT * FROM qadha_records WHERE prayer_record_id = :prayerRecordId LIMIT 1")
    suspend fun getByPrayerRecordId(prayerRecordId: String): QadhaRecordEntity?

    @Query("SELECT * FROM qadha_records ORDER BY qadha_at_epoch DESC")
    fun getAllQadhaFlow(): Flow<List<QadhaRecordEntity>>

    @Query("SELECT * FROM qadha_records WHERE sync_status = 'PENDING_SYNC'")
    suspend fun getPendingSyncQadha(): List<QadhaRecordEntity>

    @Query("DELETE FROM qadha_records")
    suspend fun clearAll()
}
