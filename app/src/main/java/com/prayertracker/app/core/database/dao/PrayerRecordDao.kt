package com.prayertracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.prayertracker.app.core.database.entity.PrayerRecordEntity
import com.prayertracker.app.core.model.PrayerName
import com.prayertracker.app.core.model.PrayerStatus
import com.prayertracker.app.core.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface PrayerRecordDao {

    @Query("SELECT * FROM prayer_records WHERE prayer_date = :date ORDER BY scheduled_time_epoch ASC")
    fun getPrayersForDateFlow(date: String): Flow<List<PrayerRecordEntity>>

    @Query("SELECT * FROM prayer_records WHERE prayer_date = :date ORDER BY scheduled_time_epoch ASC")
    suspend fun getPrayersForDate(date: String): List<PrayerRecordEntity>

    @Query("SELECT * FROM prayer_records WHERE id = :id LIMIT 1")
    suspend fun getPrayerById(id: String): PrayerRecordEntity?

    @Query("SELECT * FROM prayer_records WHERE prayer_name = :prayerName AND prayer_date = :date LIMIT 1")
    suspend fun getPrayerByNameAndDate(prayerName: PrayerName, date: String): PrayerRecordEntity?

    @Query("SELECT * FROM prayer_records WHERE status = 'MISSED' ORDER BY scheduled_time_epoch DESC")
    fun getMissedPrayersFlow(): Flow<List<PrayerRecordEntity>>

    @Query("SELECT * FROM prayer_records WHERE status = 'MISSED' ORDER BY scheduled_time_epoch DESC")
    suspend fun getMissedPrayers(): List<PrayerRecordEntity>

    @Query("SELECT * FROM prayer_records ORDER BY scheduled_time_epoch DESC")
    fun getAllPrayersFlow(): Flow<List<PrayerRecordEntity>>

    @Query("SELECT * FROM prayer_records ORDER BY scheduled_time_epoch DESC")
    suspend fun getAllPrayers(): List<PrayerRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(prayers: List<PrayerRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(prayer: PrayerRecordEntity)

    @Update
    suspend fun update(prayer: PrayerRecordEntity)

    @Query("""
        UPDATE prayer_records
        SET status = :status,
            completed_at_epoch = :completedAt,
            updated_at_epoch = :updatedAt,
            sync_status = :syncStatus
        WHERE id = :id
    """)
    suspend fun updateStatus(
        id: String,
        status: PrayerStatus,
        completedAt: Long?,
        updatedAt: Long = System.currentTimeMillis(),
        syncStatus: SyncStatus = SyncStatus.PENDING_SYNC
    ): Int

    @Query("""
        UPDATE prayer_records
        SET status = 'MISSED',
            updated_at_epoch = :updatedAt,
            sync_status = 'PENDING_SYNC'
        WHERE (status = 'PENDING' OR status = 'OTW')
          AND end_time_epoch < :currentTimeEpoch
          AND scheduled_time_epoch >= :installedAtEpoch
    """)
    suspend fun reconcileMissedPrayers(
        currentTimeEpoch: Long,
        installedAtEpoch: Long = 0L,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("DELETE FROM prayer_records")
    suspend fun clearAll()

    @Query("DELETE FROM prayer_records WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM prayer_records WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("SELECT * FROM prayer_records WHERE sync_status = 'PENDING_SYNC'")
    suspend fun getPendingSyncPrayers(): List<PrayerRecordEntity>

    @Query("SELECT COUNT(*) FROM prayer_records WHERE status = 'COMPLETED'")
    fun getCompletedCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM prayer_records WHERE status = 'MISSED'")
    fun getMissedCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM prayer_records WHERE status = 'QADHA_COMPLETED'")
    fun getQadhaCompletedCountFlow(): Flow<Int>
}
