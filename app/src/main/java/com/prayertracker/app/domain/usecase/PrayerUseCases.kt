package com.prayertracker.app.domain.usecase

import com.prayertracker.app.domain.model.PrayerItem
import com.prayertracker.app.domain.model.QadhaItem
import com.prayertracker.app.domain.model.StatisticsData
import com.prayertracker.app.domain.repository.PrayerRepository
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

class GetTodayPrayersUseCase(private val repository: PrayerRepository) {
    suspend fun ensureAndGet(
        date: LocalDate = LocalDate.now(),
        latitude: Double = -6.2088, // Default Jakarta
        longitude: Double = 106.8456,
        zoneId: ZoneId = ZoneId.systemDefault(),
        installedAtEpoch: Long = 0L
    ): List<PrayerItem> {
        // Reconcile any overdue past prayers first
        repository.reconcileMissedPrayers(installedAtEpoch)
        return repository.ensurePrayersGeneratedForDate(date, latitude, longitude, zoneId, installedAtEpoch)
    }

    fun observeToday(date: LocalDate = LocalDate.now()): Flow<List<PrayerItem>> {
        return repository.getTodayPrayersFlow(date)
    }
}

class ConfirmPrayerUseCase(private val repository: PrayerRepository) {
    suspend operator fun invoke(id: String): Result<Unit> {
        return repository.confirmPrayer(id)
    }
}

class ProcessNoUseCase(private val repository: PrayerRepository) {
    suspend operator fun invoke(id: String): Result<Unit> {
        return repository.processNo(id)
    }
}

class ProcessOtwUseCase(private val repository: PrayerRepository) {
    suspend operator fun invoke(id: String): Result<Unit> {
        return repository.processOtw(id)
    }
}

class MarkPrayerMissedUseCase(private val repository: PrayerRepository) {
    suspend operator fun invoke(id: String): Result<Unit> {
        return repository.markPrayerMissed(id)
    }
}

class ReconcileMissedPrayersUseCase(private val repository: PrayerRepository) {
    suspend operator fun invoke(installedAtEpoch: Long = 0L): Int {
        return repository.reconcileMissedPrayers(installedAtEpoch)
    }
}

class ResetAllDataUseCase(private val repository: PrayerRepository) {
    suspend operator fun invoke(): Result<Unit> {
        return repository.resetAllData()
    }
}

class PerformQadhaUseCase(private val repository: PrayerRepository) {
    suspend operator fun invoke(prayerRecordId: String, notes: String? = null): Result<Unit> {
        return repository.performQadha(prayerRecordId, notes)
    }
}

class GetQadhaListUseCase(private val repository: PrayerRepository) {
    operator fun invoke(): Flow<List<QadhaItem>> {
        return repository.getPendingQadhaFlow()
    }
}

class GetHistoryUseCase(private val repository: PrayerRepository) {
    operator fun invoke(): Flow<List<PrayerItem>> {
        return repository.getAllHistoryFlow()
    }
}

class GetStatisticsUseCase(private val repository: PrayerRepository) {
    operator fun invoke(): Flow<StatisticsData> {
        return repository.getStatisticsFlow()
    }
}
