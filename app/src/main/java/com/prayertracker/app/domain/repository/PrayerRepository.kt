package com.prayertracker.app.domain.repository

import com.prayertracker.app.domain.model.PrayerItem
import com.prayertracker.app.domain.model.QadhaItem
import com.prayertracker.app.domain.model.StatisticsData
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

interface PrayerRepository {
    fun getTodayPrayersFlow(date: LocalDate): Flow<List<PrayerItem>>
    suspend fun getTodayPrayers(date: LocalDate): List<PrayerItem>
    suspend fun ensurePrayersGeneratedForDate(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zoneId: ZoneId,
        installedAtEpoch: Long = 0L
    ): List<PrayerItem>

    suspend fun confirmPrayer(id: String, completedAtEpoch: Long = System.currentTimeMillis()): Result<Unit>
    suspend fun processNo(id: String): Result<Unit>
    suspend fun processOtw(id: String): Result<Unit>
    suspend fun markPrayerMissed(id: String): Result<Unit>
    suspend fun reconcileMissedPrayers(installedAtEpoch: Long = 0L): Int
    suspend fun resetAllData(): Result<Unit>

    suspend fun performQadha(prayerRecordId: String, notes: String? = null): Result<Unit>
    fun getPendingQadhaFlow(): Flow<List<QadhaItem>>
    fun getAllHistoryFlow(): Flow<List<PrayerItem>>
    fun getStatisticsFlow(): Flow<StatisticsData>
    suspend fun getPrayerById(id: String): PrayerItem?
}
