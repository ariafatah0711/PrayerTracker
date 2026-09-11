package com.prayertracker.app.data.repository

import com.prayertracker.app.core.database.dao.PrayerRecordDao
import com.prayertracker.app.core.database.dao.QadhaRecordDao
import com.prayertracker.app.core.database.dao.ReminderLogDao
import com.prayertracker.app.core.database.entity.PrayerRecordEntity
import com.prayertracker.app.core.database.entity.QadhaRecordEntity
import com.prayertracker.app.core.database.entity.ReminderLogEntity
import com.prayertracker.app.core.model.*
import com.prayertracker.app.domain.calculation.PrayerTimeProvider
import com.prayertracker.app.domain.model.PrayerItem
import com.prayertracker.app.domain.model.QadhaItem
import com.prayertracker.app.domain.model.StatisticsData
import com.prayertracker.app.domain.repository.PrayerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class PrayerRepositoryImpl(
    private val prayerDao: PrayerRecordDao,
    private val qadhaDao: QadhaRecordDao,
    private val reminderLogDao: ReminderLogDao,
    private val prayerTimeProvider: PrayerTimeProvider
) : PrayerRepository {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    override fun getTodayPrayersFlow(date: LocalDate): Flow<List<PrayerItem>> {
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        return prayerDao.getPrayersForDateFlow(dateStr).map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun getTodayPrayers(date: LocalDate): List<PrayerItem> {
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        return prayerDao.getPrayersForDate(dateStr).map { it.toDomain() }
    }

    override suspend fun ensurePrayersGeneratedForDate(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zoneId: ZoneId,
        installedAtEpoch: Long
    ): List<PrayerItem> {
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val existing = prayerDao.getPrayersForDate(dateStr)
        val times = prayerTimeProvider.calculatePrayerTimes(
            date = date,
            latitude = latitude,
            longitude = longitude,
            zoneId = zoneId
        )
        if (existing.isNotEmpty()) {
            val timeMap = mapOf(
                PrayerName.FAJR to Pair(times.fajrEpoch, times.sunriseEpoch),
                PrayerName.DHUHR to Pair(times.dhuhrEpoch, times.asrEpoch),
                PrayerName.ASR to Pair(times.asrEpoch, times.maghribEpoch),
                PrayerName.MAGHRIB to Pair(times.maghribEpoch, times.ishaEpoch),
                PrayerName.ISHA to Pair(times.ishaEpoch, times.nextFajrEpoch)
            )
            val updated = existing.map { entity ->
                val newTimes = timeMap[entity.prayerName]
                if (newTimes != null && (entity.scheduledTimeEpoch != newTimes.first || entity.endTimeEpoch != newTimes.second)) {
                    val mod = entity.copy(
                        scheduledTimeEpoch = newTimes.first,
                        endTimeEpoch = newTimes.second
                    )
                    prayerDao.update(mod)
                    mod
                } else entity
            }
            return updated.map { it.toDomain() }
        }

        val nowEpoch = System.currentTimeMillis()

        fun resolveInitialStatus(endEpoch: Long): PrayerStatus {
            return if (nowEpoch > endEpoch) {
                // Jika waktu salat berakhir SEBELUM user menginstal aplikasi / sebelum reset,
                // tandai COMPLETED agar tidak mengotori daftar Qadha!
                if (installedAtEpoch > 0 && endEpoch < installedAtEpoch) {
                    PrayerStatus.COMPLETED
                } else {
                    PrayerStatus.MISSED
                }
            } else {
                PrayerStatus.PENDING
            }
        }

        val entities = listOf(
            PrayerRecordEntity(
                id = UUID.randomUUID().toString(),
                prayerName = PrayerName.FAJR,
                prayerDate = dateStr,
                scheduledTimeEpoch = times.fajrEpoch,
                endTimeEpoch = times.sunriseEpoch,
                status = resolveInitialStatus(times.sunriseEpoch)
            ),
            PrayerRecordEntity(
                id = UUID.randomUUID().toString(),
                prayerName = PrayerName.DHUHR,
                prayerDate = dateStr,
                scheduledTimeEpoch = times.dhuhrEpoch,
                endTimeEpoch = times.asrEpoch,
                status = resolveInitialStatus(times.asrEpoch)
            ),
            PrayerRecordEntity(
                id = UUID.randomUUID().toString(),
                prayerName = PrayerName.ASR,
                prayerDate = dateStr,
                scheduledTimeEpoch = times.asrEpoch,
                endTimeEpoch = times.maghribEpoch,
                status = resolveInitialStatus(times.maghribEpoch)
            ),
            PrayerRecordEntity(
                id = UUID.randomUUID().toString(),
                prayerName = PrayerName.MAGHRIB,
                prayerDate = dateStr,
                scheduledTimeEpoch = times.maghribEpoch,
                endTimeEpoch = times.ishaEpoch,
                status = resolveInitialStatus(times.ishaEpoch)
            ),
            PrayerRecordEntity(
                id = UUID.randomUUID().toString(),
                prayerName = PrayerName.ISHA,
                prayerDate = dateStr,
                scheduledTimeEpoch = times.ishaEpoch,
                endTimeEpoch = times.nextFajrEpoch,
                status = resolveInitialStatus(times.nextFajrEpoch)
            )
        )

        prayerDao.insertAll(entities)
        return prayerDao.getPrayersForDate(dateStr).map { it.toDomain() }
    }

    override suspend fun confirmPrayer(id: String, completedAtEpoch: Long): Result<Unit> {
        val prayer = prayerDao.getPrayerById(id) ?: return Result.failure(IllegalArgumentException("Prayer not found"))
        if (prayer.status.isTerminal && prayer.completedAtEpoch != null) {
            // Already completed with recorded timestamp - idempotent success
            return Result.success(Unit)
        }

        prayerDao.updateStatus(
            id = id,
            status = PrayerStatus.COMPLETED,
            completedAt = completedAtEpoch
        )

        reminderLogDao.insert(
            ReminderLogEntity(
                id = UUID.randomUUID().toString(),
                prayerRecordId = id,
                reminderType = ReminderType.INITIAL,
                triggeredAtEpoch = completedAtEpoch,
                userResponse = UserResponse.YES
            )
        )
        return Result.success(Unit)
    }

    override suspend fun processNo(id: String): Result<Unit> {
        val prayer = prayerDao.getPrayerById(id) ?: return Result.failure(IllegalArgumentException("Prayer not found"))
        if (prayer.status.isTerminal) return Result.success(Unit)

        // Status remains PENDING
        reminderLogDao.insert(
            ReminderLogEntity(
                id = UUID.randomUUID().toString(),
                prayerRecordId = id,
                reminderType = ReminderType.SNOOZE_NO,
                triggeredAtEpoch = System.currentTimeMillis(),
                userResponse = UserResponse.NO
            )
        )
        return Result.success(Unit)
    }

    override suspend fun processOtw(id: String): Result<Unit> {
        val prayer = prayerDao.getPrayerById(id) ?: return Result.failure(IllegalArgumentException("Prayer not found"))
        if (prayer.status.isTerminal) return Result.success(Unit)

        prayerDao.updateStatus(
            id = id,
            status = PrayerStatus.OTW,
            completedAt = null
        )

        reminderLogDao.insert(
            ReminderLogEntity(
                id = UUID.randomUUID().toString(),
                prayerRecordId = id,
                reminderType = ReminderType.OTW_FOLLOWUP,
                triggeredAtEpoch = System.currentTimeMillis(),
                userResponse = UserResponse.OTW
            )
        )
        return Result.success(Unit)
    }

    override suspend fun markPrayerMissed(id: String): Result<Unit> {
        val prayer = prayerDao.getPrayerById(id) ?: return Result.failure(IllegalArgumentException("Prayer not found"))
        prayerDao.updateStatus(
            id = id,
            status = PrayerStatus.MISSED,
            completedAt = null
        )
        return Result.success(Unit)
    }

    override suspend fun reconcileMissedPrayers(installedAtEpoch: Long): Int {
        return prayerDao.reconcileMissedPrayers(
            currentTimeEpoch = System.currentTimeMillis(),
            installedAtEpoch = installedAtEpoch
        )
    }

    override suspend fun resetAllData(): Result<Unit> {
        return try {
            qadhaDao.clearAll()
            reminderLogDao.clearAll()
            prayerDao.clearAll()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun performQadha(prayerRecordId: String, notes: String?): Result<Unit> {
        return try {
            val prayer = prayerDao.getPrayerById(prayerRecordId)
                ?: return Result.failure(IllegalArgumentException("Data salat tidak ditemukan (ID: $prayerRecordId)"))

            val now = System.currentTimeMillis()

            // Hapus rekaman qadha lama jika ada agar tidak bentrok foreign key / unique index
            qadhaDao.deleteByPrayerRecordId(prayerRecordId)

            // Simpan audit record qadha baru
            qadhaDao.insert(
                QadhaRecordEntity(
                    id = UUID.randomUUID().toString(),
                    prayerRecordId = prayerRecordId,
                    qadhaStatus = PrayerStatus.QADHA_COMPLETED,
                    qadhaAtEpoch = now,
                    notes = notes,
                    syncStatus = SyncStatus.PENDING_SYNC
                )
            )

            // Update status salat menjadi QADHA_COMPLETED
            prayerDao.updateStatus(
                id = prayerRecordId,
                status = PrayerStatus.QADHA_COMPLETED,
                completedAt = now,
                updatedAt = now,
                syncStatus = SyncStatus.PENDING_SYNC
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getPendingQadhaFlow(): Flow<List<QadhaItem>> {
        return prayerDao.getMissedPrayersFlow().map { list ->
            list.map {
                QadhaItem(
                    prayerRecordId = it.id,
                    prayerName = it.prayerName,
                    originalDate = it.prayerDate,
                    originalScheduledEpoch = it.scheduledTimeEpoch,
                    formattedScheduledTime = formatEpoch(it.scheduledTimeEpoch),
                    status = it.status
                )
            }
        }
    }

    override fun getAllHistoryFlow(): Flow<List<PrayerItem>> {
        return prayerDao.getAllPrayersFlow().map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun getStatisticsFlow(): Flow<StatisticsData> {
        return combine(
            prayerDao.getCompletedCountFlow(),
            prayerDao.getMissedCountFlow(),
            prayerDao.getQadhaCompletedCountFlow()
        ) { completed, missed, qadha ->
            val total = completed + missed + qadha
            val percentage = if (total > 0) ((completed.toDouble() / total.toDouble()) * 100).toInt() else 100
            val streak = if (missed == 0 && completed > 0) (completed / 5) else 0

            StatisticsData(
                completedCount = completed,
                missedCount = missed,
                qadhaCompletedCount = qadha,
                completionPercentage = percentage,
                streakDays = streak
            )
        }
    }

    override suspend fun getPrayerById(id: String): PrayerItem? {
        return prayerDao.getPrayerById(id)?.toDomain()
    }

    private fun PrayerRecordEntity.toDomain(): PrayerItem {
        return PrayerItem(
            id = id,
            prayerName = prayerName,
            prayerDate = prayerDate,
            scheduledEpoch = scheduledTimeEpoch,
            endEpoch = endTimeEpoch,
            status = status,
            completedAtEpoch = completedAtEpoch,
            formattedScheduledTime = formatEpoch(scheduledTimeEpoch),
            formattedEndTime = formatEpoch(endTimeEpoch),
            formattedCompletedTime = completedAtEpoch?.let { formatEpoch(it) }
        )
    }

    private fun formatEpoch(epoch: Long): String {
        return Instant.ofEpochMilli(epoch)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)
    }
}
