package com.prayertracker.app

import android.app.Application
import com.prayertracker.app.core.database.AppDatabase
import com.prayertracker.app.core.datastore.AppSettingsRepository
import com.prayertracker.app.data.repository.PrayerRepositoryImpl
import com.prayertracker.app.domain.calculation.AstronomicalPrayerCalculator
import com.prayertracker.app.domain.repository.PrayerRepository
import com.prayertracker.app.domain.usecase.*
import com.prayertracker.app.core.model.PrayerStatus
import com.prayertracker.app.core.util.PrayerDateTimeUtils
import com.prayertracker.app.notification.NotificationHelper
import com.prayertracker.app.scheduler.PrayerAlarmScheduler
import com.prayertracker.app.ui.overlay.PrayerAlarmDialogActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

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
        val sheetsSyncManager = com.prayertracker.app.core.sync.GoogleSheetsSyncManager(database, googleAuthManager, settingsRepository)
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

        // Daftarkan listener layar nyala / buka HP
        val screenFilter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, screenFilter)

        // Periksa apakah saat aplikasi baru dibuka/di-update sedang ada waktu salat aktif
        checkAndShowOngoingPrayerOverlay()
    }

    private var lastScreenOnOverlayEpoch = 0L

    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val action = intent?.action ?: return
            if (action == android.content.Intent.ACTION_SCREEN_ON || action == android.content.Intent.ACTION_USER_PRESENT) {
                checkAndShowOngoingPrayerOverlay()
            }
        }
    }

    /**
     * Memeriksa apakah ada waktu salat yang sedang berlangsung dan belum direspon.
     * Memastikan notifikasi status bar selalu tampil aktif, dan jika layar menyala serta
     * user TIDAK sedang dalam masa tunda eksplisit (klik BELUM/OTW),
     * dialog overlay akan langsung dimunculkan kembali.
     */
    fun checkAndShowOngoingPrayerOverlay() {
        val now = System.currentTimeMillis()
        if (now - lastScreenOnOverlayEpoch < 3000L) return // Debounce 3 detik
        if (PrayerAlarmScheduler.isExplicitlySnoozed()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = settingsRepository.settingsFlow.first()
                val todayPrayers = repository.getTodayPrayersFlow(LocalDate.now()).first()
                val ongoingPrayer = todayPrayers.find { p ->
                    val effEnd = PrayerDateTimeUtils.calculateEffectiveEndTime(
                        p.scheduledEpoch,
                        p.endEpoch
                    )
                    (p.status == PrayerStatus.PENDING || p.status == PrayerStatus.OTW) &&
                            now >= p.scheduledEpoch &&
                            now < effEnd
                } ?: return@launch

                val notificationId = PrayerAlarmScheduler.getNotificationId(ongoingPrayer.prayerName.order)

                // 1. Pastikan notifikasi status bar selalu hadir saat waktu salat sedang berlangsung
                if (ongoingPrayer.status == PrayerStatus.PENDING) {
                    notificationHelper.showPrayerIncomingNotification(
                        prayerId = ongoingPrayer.id,
                        prayerName = ongoingPrayer.prayerName.displayName,
                        timeFormatted = ongoingPrayer.formattedScheduledTime,
                        notificationId = notificationId,
                        otwMinutes = settings.otwIntervalMinutes,
                        noSnoozeMinutes = settings.noSnoozeIntervalMinutes
                    )
                } else if (ongoingPrayer.status == PrayerStatus.OTW) {
                    notificationHelper.showOtwFollowUpNotification(
                        prayerId = ongoingPrayer.id,
                        prayerName = ongoingPrayer.prayerName.displayName,
                        notificationId = notificationId,
                        noSnoozeMinutes = settings.noSnoozeIntervalMinutes
                    )
                }

                // 2. Tampilkan dialog overlay jika didukung dan tidak sedang terbuka
                if (PrayerAlarmDialogActivity.isOverlayShowing) return@launch
                if (!settings.isOverlayEnabled) return@launch

                val canDraw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    android.provider.Settings.canDrawOverlays(this@PrayerTrackerApp)
                } else true
                if (!canDraw) return@launch

                lastScreenOnOverlayEpoch = now
                val overlayIntent = PrayerAlarmDialogActivity.createIntent(
                    context = this@PrayerTrackerApp,
                    prayerId = ongoingPrayer.id,
                    prayerName = ongoingPrayer.prayerName.displayName,
                    timeFormatted = ongoingPrayer.formattedScheduledTime,
                    notificationId = notificationId,
                    alertType = if (ongoingPrayer.status == PrayerStatus.OTW) "OTW" else "ENTRY"
                )
                startActivity(overlayIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
