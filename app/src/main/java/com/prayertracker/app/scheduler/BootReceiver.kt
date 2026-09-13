package com.prayertracker.app.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.prayertracker.app.PrayerTrackerApp
import com.prayertracker.app.core.model.PrayerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val pendingResult = goAsync()
            val app = context.applicationContext as PrayerTrackerApp
            val repository = app.repository
            val alarmScheduler = app.alarmScheduler
            val settingsRepo = app.settingsRepository
            val notificationHelper = app.notificationHelper

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val settings = settingsRepo.settingsFlow.first()

                    // Reconcile past prayers first
                    repository.reconcileMissedPrayers()

                    // Generate / fetch today's prayers
                    val todayPrayers = repository.ensurePrayersGeneratedForDate(
                        date = LocalDate.now(),
                        latitude = settings.latitude,
                        longitude = settings.longitude,
                        zoneId = ZoneId.systemDefault()
                    )

                    val now = System.currentTimeMillis()

                    // Reschedule upcoming prayer alarms OR restore ongoing prayer notification
                    todayPrayers.forEachIndexed { index, prayer ->
                        val notifId = PrayerAlarmScheduler.getNotificationId(prayer.prayerName.order)
                        val effEnd = com.prayertracker.app.core.util.PrayerDateTimeUtils.calculateEffectiveEndTime(
                            prayer.scheduledEpoch,
                            prayer.endEpoch
                        )

                        if (prayer.status == PrayerStatus.PENDING) {
                            if (prayer.scheduledEpoch > now) {
                                alarmScheduler.schedulePrayerEntry(
                                    prayerId = prayer.id,
                                    prayerName = prayer.prayerName.displayName,
                                    triggerEpoch = prayer.scheduledEpoch,
                                    notificationId = notifId
                                )
                            } else if (now < effEnd) {
                                // Waktu salat sedang berlangsung saat HP baru nyala / app baru di-update!
                                if (!PrayerAlarmScheduler.isExplicitlySnoozed()) {
                                    notificationHelper.showPrayerIncomingNotification(
                                        prayerId = prayer.id,
                                        prayerName = prayer.prayerName.displayName,
                                        timeFormatted = prayer.formattedScheduledTime,
                                        notificationId = notifId,
                                        otwMinutes = settings.otwIntervalMinutes,
                                        noSnoozeMinutes = settings.noSnoozeIntervalMinutes
                                    )
                                    // Munculkan overlay jika diizinkan
                                    app.checkAndShowOngoingPrayerOverlay()
                                }
                            }
                        } else if (prayer.status == PrayerStatus.OTW && now < effEnd) {
                            notificationHelper.showOtwFollowUpNotification(
                                prayerId = prayer.id,
                                prayerName = prayer.prayerName.displayName,
                                notificationId = notifId,
                                noSnoozeMinutes = settings.noSnoozeIntervalMinutes
                            )
                        }
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
