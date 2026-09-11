package com.prayertracker.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.prayertracker.app.PrayerTrackerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PrayerNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prayerId = intent.getStringExtra(NotificationHelper.EXTRA_PRAYER_ID) ?: return
        val prayerName = intent.getStringExtra(NotificationHelper.EXTRA_PRAYER_NAME) ?: "Salat"
        val notificationId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIFICATION_ID, 1001)

        val pendingResult = goAsync()
        val app = context.applicationContext as PrayerTrackerApp
        val repository = app.repository
        val alarmScheduler = app.alarmScheduler
        val notificationHelper = app.notificationHelper
        val settingsRepo = app.settingsRepository

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = settingsRepo.settingsFlow.first()

                when (intent.action) {
                    NotificationHelper.ACTION_PRAYER_YES -> {
                        // User clicked YES
                        repository.confirmPrayer(prayerId)
                        notificationHelper.cancelNotification(notificationId)
                        // Cancel any pending snooze / OTW alarms
                        alarmScheduler.cancelAlarm(notificationId + 1000)
                        alarmScheduler.cancelAlarm(notificationId + 2000)
                    }

                    NotificationHelper.ACTION_PRAYER_NO -> {
                        // User clicked NO / BELUM
                        repository.processNo(prayerId)
                        notificationHelper.cancelNotification(notificationId)
                        // Schedule Snooze reminder (default 10 minutes)
                        alarmScheduler.scheduleNoSnooze(
                            prayerId = prayerId,
                            prayerName = prayerName,
                            delayMinutes = settings.noSnoozeIntervalMinutes,
                            notificationId = notificationId
                        )
                    }

                    NotificationHelper.ACTION_PRAYER_OTW -> {
                        // User clicked OTW
                        repository.processOtw(prayerId)
                        notificationHelper.cancelNotification(notificationId)
                        // Schedule OTW follow-up reminder (default 3 minutes)
                        alarmScheduler.scheduleOtwFollowUp(
                            prayerId = prayerId,
                            prayerName = prayerName,
                            delayMinutes = settings.otwIntervalMinutes,
                            notificationId = notificationId
                        )
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
