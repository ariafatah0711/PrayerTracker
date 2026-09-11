package com.prayertracker.app

import android.app.Application
import com.prayertracker.app.core.database.AppDatabase
import com.prayertracker.app.core.datastore.AppSettingsRepository
import com.prayertracker.app.data.repository.PrayerRepositoryImpl
import com.prayertracker.app.domain.calculation.AstronomicalPrayerCalculator
import com.prayertracker.app.domain.repository.PrayerRepository
import com.prayertracker.app.domain.usecase.*
import com.prayertracker.app.notification.NotificationHelper
import com.prayertracker.app.scheduler.PrayerAlarmScheduler

class PrayerTrackerApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var repository: PrayerRepository
        private set

    lateinit var notificationHelper: NotificationHelper
        private set

    lateinit var alarmScheduler: PrayerAlarmScheduler
        private set

    lateinit var settingsRepository: AppSettingsRepository
        private set

    lateinit var googleAuthManager: com.prayertracker.app.core.sync.GoogleAuthManager
        private set

    lateinit var syncCoordinator: com.prayertracker.app.core.sync.SyncCoordinator
        private set

    lateinit var localBackupManager: com.prayertracker.app.core.sync.LocalBackupManager
        private set

    // UseCases
    lateinit var getTodayPrayersUseCase: GetTodayPrayersUseCase
        private set
    lateinit var confirmPrayerUseCase: ConfirmPrayerUseCase
        private set
    lateinit var processNoUseCase: ProcessNoUseCase
        private set
    lateinit var processOtwUseCase: ProcessOtwUseCase
        private set
    lateinit var reconcileMissedPrayersUseCase: ReconcileMissedPrayersUseCase
        private set
    lateinit var performQadhaUseCase: PerformQadhaUseCase
        private set
    lateinit var getQadhaListUseCase: GetQadhaListUseCase
        private set
    lateinit var getHistoryUseCase: GetHistoryUseCase
        private set
    lateinit var getStatisticsUseCase: GetStatisticsUseCase
        private set
    lateinit var resetAllDataUseCase: ResetAllDataUseCase
        private set
    lateinit var markPrayerMissedUseCase: MarkPrayerMissedUseCase
        private set

    override fun onCreate() {
        super.onCreate()

        database = AppDatabase.getInstance(this)
        settingsRepository = AppSettingsRepository(this)
        notificationHelper = NotificationHelper(this)
        alarmScheduler = PrayerAlarmScheduler(this)

        googleAuthManager = com.prayertracker.app.core.sync.GoogleAuthManager(this)
        val driveBackupManager = com.prayertracker.app.core.sync.GoogleDriveBackupManager(database, googleAuthManager)
        val sheetsSyncManager = com.prayertracker.app.core.sync.GoogleSheetsSyncManager(database, googleAuthManager)
        syncCoordinator = com.prayertracker.app.core.sync.SyncCoordinator(database, driveBackupManager, sheetsSyncManager, settingsRepository)
        localBackupManager = com.prayertracker.app.core.sync.LocalBackupManager(database)

        val calculator = AstronomicalPrayerCalculator()
        repository = PrayerRepositoryImpl(
            prayerDao = database.prayerRecordDao(),
            qadhaDao = database.qadhaRecordDao(),
            reminderLogDao = database.reminderLogDao(),
            prayerTimeProvider = calculator
        )

        // Initialize UseCases
        getTodayPrayersUseCase = GetTodayPrayersUseCase(repository)
        confirmPrayerUseCase = ConfirmPrayerUseCase(repository)
        processNoUseCase = ProcessNoUseCase(repository)
        processOtwUseCase = ProcessOtwUseCase(repository)
        markPrayerMissedUseCase = MarkPrayerMissedUseCase(repository)
        reconcileMissedPrayersUseCase = ReconcileMissedPrayersUseCase(repository)
        performQadhaUseCase = PerformQadhaUseCase(repository)
        getQadhaListUseCase = GetQadhaListUseCase(repository)
        getHistoryUseCase = GetHistoryUseCase(repository)
        getStatisticsUseCase = GetStatisticsUseCase(repository)
        resetAllDataUseCase = ResetAllDataUseCase(repository)
    }
}
