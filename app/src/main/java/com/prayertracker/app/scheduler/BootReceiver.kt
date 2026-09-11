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

                    // Reschedule upcoming prayer alarms
                    todayPrayers.forEachIndexed { index, prayer ->
                        if (prayer.status == PrayerStatus.PENDING && prayer.scheduledEpoch > System.currentTimeMillis()) {
                            alarmScheduler.schedulePrayerEntry(
                                prayerId = prayer.id,
                                prayerName = prayer.prayerName.displayName,
                                triggerEpoch = prayer.scheduledEpoch,
                                notificationId = 1000 + index
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
