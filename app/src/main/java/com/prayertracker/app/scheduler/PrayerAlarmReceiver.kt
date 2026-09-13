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

class PrayerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prayerId = intent.getStringExtra(PrayerAlarmScheduler.EXTRA_PRAYER_ID) ?: return
        val prayerName = intent.getStringExtra(PrayerAlarmScheduler.EXTRA_PRAYER_NAME) ?: "Salat"
        val notificationId = intent.getIntExtra(PrayerAlarmScheduler.EXTRA_NOTIFICATION_ID, 1001)

        val pendingResult = goAsync()
        val app = context.applicationContext as PrayerTrackerApp
        val notificationHelper = app.notificationHelper
        val repository = app.repository
        val settingsRepo = app.settingsRepository

        CoroutineScope(Dispatchers.IO).launch {
            try {
                PrayerAlarmScheduler.clearExplicitSnooze()
                // Reconcile missed prayers first
                repository.reconcileMissedPrayers()

                val settings = settingsRepo.settingsFlow.first()
                val canDraw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    android.provider.Settings.canDrawOverlays(context)
                } else true

                if (prayerId.startsWith("test_")) {
                    fun launchTestOverlay(alertType: String) {
                        if (settings.isOverlayEnabled && canDraw) {
                            val overlayIntent = com.prayertracker.app.ui.overlay.PrayerAlarmDialogActivity.createIntent(
                                context = context,
                                prayerId = prayerId,
                                prayerName = prayerName,
                                timeFormatted = "Uji Coba",
                                notificationId = notificationId,
                                alertType = alertType
                            )
                            try {
                                context.startActivity(overlayIntent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }

                    when (intent.action) {
                        PrayerAlarmScheduler.ACTION_ALARM_OTW_FOLLOWUP -> {
                            notificationHelper.showOtwFollowUpNotification(
                                prayerId = prayerId,
                                prayerName = prayerName,
                                notificationId = notificationId,
                                noSnoozeMinutes = settings.noSnoozeIntervalMinutes
                            )
                            launchTestOverlay("OTW")
                        }
                        PrayerAlarmScheduler.ACTION_ALARM_NO_SNOOZE -> {
                            notificationHelper.showSnoozeReminderNotification(
                                prayerId = prayerId,
                                prayerName = prayerName,
                                notificationId = notificationId,
                                otwMinutes = settings.otwIntervalMinutes,
                                noSnoozeMinutes = settings.noSnoozeIntervalMinutes
                            )
                            launchTestOverlay("SNOOZE")
                        }
                        else -> {
                            notificationHelper.showPrayerIncomingNotification(
                                prayerId = prayerId,
                                prayerName = prayerName,
                                timeFormatted = "Sekarang",
                                notificationId = notificationId,
                                otwMinutes = settings.otwIntervalMinutes,
                                noSnoozeMinutes = settings.noSnoozeIntervalMinutes
                            )
                            launchTestOverlay("ENTRY")
                        }
                    }
                    return@launch
                }

                val prayer = repository.getPrayerById(prayerId)
                if (prayer != null) {
                    fun launchOverlay(alertType: String) {
                        if (settings.isOverlayEnabled && canDraw) {
                            val overlayIntent = com.prayertracker.app.ui.overlay.PrayerAlarmDialogActivity.createIntent(
                                context = context,
                                prayerId = prayerId,
                                prayerName = prayerName,
                                timeFormatted = prayer.formattedScheduledTime,
                                notificationId = notificationId,
                                alertType = alertType
                            )
                            try {
                                context.startActivity(overlayIntent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }

                    val now = System.currentTimeMillis()

                    when (intent.action) {
                        PrayerAlarmScheduler.ACTION_ALARM_PRAYER_ENTRY -> {
                            // Pastikan status masih PENDING dan waktu salat belum berakhir
                            if (prayer.status == PrayerStatus.PENDING && now < prayer.endEpoch) {
                                val isSeverelyDelayed = (now - prayer.scheduledEpoch) > 20 * 60 * 1000L
                                if (isSeverelyDelayed) {
                                    // Terlambat > 20 menit dari jadwal (misal HP baru dinyalakan/keluar Doze)
                                    // Tampilkan sebagai pengingat tenang, bukan alarm 'baru masuk'
                                    notificationHelper.showSnoozeReminderNotification(
                                        prayerId = prayerId,
                                        prayerName = prayerName,
                                        notificationId = notificationId,
                                        otwMinutes = settings.otwIntervalMinutes,
                                        noSnoozeMinutes = settings.noSnoozeIntervalMinutes
                                    )
                                } else {
                                    notificationHelper.showPrayerIncomingNotification(
                                        prayerId = prayerId,
                                        prayerName = prayerName,
                                        timeFormatted = prayer.formattedScheduledTime,
                                        notificationId = notificationId,
                                        otwMinutes = settings.otwIntervalMinutes,
                                        noSnoozeMinutes = settings.noSnoozeIntervalMinutes
                                    )
                                    launchOverlay("ENTRY")
                                }
                            }
                        }
                        PrayerAlarmScheduler.ACTION_ALARM_OTW_FOLLOWUP -> {
                            if (prayer.status == PrayerStatus.OTW && now < prayer.endEpoch) {
                                notificationHelper.showOtwFollowUpNotification(
                                    prayerId = prayerId,
                                    prayerName = prayerName,
                                    notificationId = notificationId,
                                    noSnoozeMinutes = settings.noSnoozeIntervalMinutes
                                )
                                launchOverlay("OTW")
                            }
                        }
                        PrayerAlarmScheduler.ACTION_ALARM_NO_SNOOZE -> {
                            if (prayer.status == PrayerStatus.PENDING && now < prayer.endEpoch) {
                                notificationHelper.showSnoozeReminderNotification(
                                    prayerId = prayerId,
                                    prayerName = prayerName,
                                    notificationId = notificationId,
                                    otwMinutes = settings.otwIntervalMinutes,
                                    noSnoozeMinutes = settings.noSnoozeIntervalMinutes
                                )
                                launchOverlay("SNOOZE")
                            }
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
