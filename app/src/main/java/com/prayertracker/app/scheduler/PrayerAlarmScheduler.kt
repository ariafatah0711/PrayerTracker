package com.prayertracker.app.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

class PrayerAlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val ACTION_ALARM_PRAYER_ENTRY = "com.prayertracker.app.ACTION_ALARM_PRAYER_ENTRY"
        const val ACTION_ALARM_OTW_FOLLOWUP = "com.prayertracker.app.ACTION_ALARM_OTW_FOLLOWUP"
        const val ACTION_ALARM_NO_SNOOZE = "com.prayertracker.app.ACTION_ALARM_NO_SNOOZE"

        const val EXTRA_PRAYER_ID = "extra_prayer_id"
        const val EXTRA_PRAYER_NAME = "extra_prayer_name"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

        const val BASE_NOTIFICATION_ID = 1000
        const val OTW_OFFSET = 1000
        const val SNOOZE_OFFSET = 2000

        fun getNotificationId(order: Int): Int = BASE_NOTIFICATION_ID + order
        fun getOtwRequestCode(notificationId: Int): Int = notificationId + OTW_OFFSET
        fun getSnoozeRequestCode(notificationId: Int): Int = notificationId + SNOOZE_OFFSET

        @Volatile
        var explicitSnoozedUntilEpoch: Long = 0L

        fun setExplicitSnooze(durationMinutes: Int) {
            explicitSnoozedUntilEpoch = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
        }

        fun clearExplicitSnooze() {
            explicitSnoozedUntilEpoch = 0L
        }

        fun isExplicitlySnoozed(): Boolean {
            return System.currentTimeMillis() < explicitSnoozedUntilEpoch
        }
    }

    fun schedulePrayerEntry(
        prayerId: String,
        prayerName: String,
        triggerEpoch: Long,
        notificationId: Int
    ) {
        if (triggerEpoch <= System.currentTimeMillis()) return

        val intent = Intent(context, PrayerAlarmReceiver::class.java).apply {
            action = ACTION_ALARM_PRAYER_ENTRY
            putExtra(EXTRA_PRAYER_ID, prayerId)
            putExtra(EXTRA_PRAYER_NAME, prayerName)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleExact(triggerEpoch, pendingIntent)
    }

    fun scheduleOtwFollowUp(
        prayerId: String,
        prayerName: String,
        delayMinutes: Int = 3,
        notificationId: Int
    ) {
        val triggerEpoch = System.currentTimeMillis() + (delayMinutes * 60 * 1000L)
        val intent = Intent(context, PrayerAlarmReceiver::class.java).apply {
            action = ACTION_ALARM_OTW_FOLLOWUP
            putExtra(EXTRA_PRAYER_ID, prayerId)
            putExtra(EXTRA_PRAYER_NAME, prayerName)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            getOtwRequestCode(notificationId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleExact(triggerEpoch, pendingIntent)
    }

    fun scheduleNoSnooze(
        prayerId: String,
        prayerName: String,
        delayMinutes: Int = 10,
        notificationId: Int
    ) {
        val triggerEpoch = System.currentTimeMillis() + (delayMinutes * 60 * 1000L)
        val intent = Intent(context, PrayerAlarmReceiver::class.java).apply {
            action = ACTION_ALARM_NO_SNOOZE
            putExtra(EXTRA_PRAYER_ID, prayerId)
            putExtra(EXTRA_PRAYER_NAME, prayerName)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            getSnoozeRequestCode(notificationId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleExact(triggerEpoch, pendingIntent)
    }

    fun cancelAlarm(requestCode: Int) {
        val intent = Intent(context, PrayerAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
        }
    }

    /**
     * Membatalkan seluruh jenis alarm (Entry, OTW, dan Snooze) untuk waktu salat tertentu secara bersih.
     */
    fun cancelAllAlarmsForPrayer(notificationId: Int) {
        cancelAlarm(notificationId)
        cancelAlarm(getOtwRequestCode(notificationId))
        cancelAlarm(getSnoozeRequestCode(notificationId))
    }

    private fun scheduleExact(triggerEpoch: Long, pendingIntent: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerEpoch, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerEpoch, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerEpoch, pendingIntent)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerEpoch, pendingIntent)
        }
    }
}
