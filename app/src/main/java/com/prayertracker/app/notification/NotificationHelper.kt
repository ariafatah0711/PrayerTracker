package com.prayertracker.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.prayertracker.app.MainActivity
import com.prayertracker.app.R

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_PRAYER_ALARM = "prayer_alarm_channel"
        const val CHANNEL_PRAYER_REMINDER = "prayer_reminder_channel"

        const val ACTION_PRAYER_YES = "com.prayertracker.app.ACTION_PRAYER_YES"
        const val ACTION_PRAYER_NO = "com.prayertracker.app.ACTION_PRAYER_NO"
        const val ACTION_PRAYER_OTW = "com.prayertracker.app.ACTION_PRAYER_OTW"

        const val EXTRA_PRAYER_ID = "extra_prayer_id"
        const val EXTRA_PRAYER_NAME = "extra_prayer_name"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val alarmChannel = NotificationChannel(
                CHANNEL_PRAYER_ALARM,
                "Waktu Salat Masuk",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi saat masuk waktu salat wajib dengan aksi cepat"
                enableVibration(true)
                setShowBadge(true)
            }

            val reminderChannel = NotificationChannel(
                CHANNEL_PRAYER_REMINDER,
                "Pengingat Salat Lanjutan",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Pengingat tindak lanjut OTW dan Snooze salat"
                enableVibration(true)
                setShowBadge(true)
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(alarmChannel)
            manager.createNotificationChannel(reminderChannel)
        }
    }

    fun showPrayerIncomingNotification(
        prayerId: String,
        prayerName: String,
        timeFormatted: String,
        notificationId: Int
    ) {
        val dialogIntent = com.prayertracker.app.ui.overlay.PrayerAlarmDialogActivity.createIntent(
            context = context,
            prayerId = prayerId,
            prayerName = prayerName,
            timeFormatted = timeFormatted,
            notificationId = notificationId,
            alertType = "ENTRY"
        )
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            dialogIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // YES Action
        val yesIntent = Intent(context, PrayerNotificationReceiver::class.java).apply {
            action = ACTION_PRAYER_YES
            putExtra(EXTRA_PRAYER_ID, prayerId)
            putExtra(EXTRA_PRAYER_NAME, prayerName)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val yesPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 10 + 1,
            yesIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // NO Action
        val noIntent = Intent(context, PrayerNotificationReceiver::class.java).apply {
            action = ACTION_PRAYER_NO
            putExtra(EXTRA_PRAYER_ID, prayerId)
            putExtra(EXTRA_PRAYER_NAME, prayerName)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val noPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 10 + 2,
            noIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // OTW Action
        val otwIntent = Intent(context, PrayerNotificationReceiver::class.java).apply {
            action = ACTION_PRAYER_OTW
            putExtra(EXTRA_PRAYER_ID, prayerId)
            putExtra(EXTRA_PRAYER_NAME, prayerName)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val otwPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 10 + 3,
            otwIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_PRAYER_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Waktu $prayerName Masuk ($timeFormatted)")
            .setContentText("Sudah masuk waktu salat $prayerName. Sudah salat?")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setFullScreenIntent(openAppPendingIntent, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.checkbox_on_background, "YES", yesPendingIntent)
            .addAction(android.R.drawable.ic_delete, "NO", noPendingIntent)
            .addAction(android.R.drawable.ic_media_play, "OTW", otwPendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {}
    }

    fun triggerTestNotification() {
        showPrayerIncomingNotification(
            prayerId = "test_preview_id",
            prayerName = "Dzuhur (Uji Coba)",
            timeFormatted = "Sekarang",
            notificationId = 9999
        )
    }

    fun showOtwFollowUpNotification(
        prayerId: String,
        prayerName: String,
        notificationId: Int
    ) {
        val dialogIntent = com.prayertracker.app.ui.overlay.PrayerAlarmDialogActivity.createIntent(
            context = context,
            prayerId = prayerId,
            prayerName = prayerName,
            timeFormatted = "Selesai Salat?",
            notificationId = notificationId,
            alertType = "OTW"
        )
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            dialogIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // YES Action
        val yesIntent = Intent(context, PrayerNotificationReceiver::class.java).apply {
            action = ACTION_PRAYER_YES
            putExtra(EXTRA_PRAYER_ID, prayerId)
            putExtra(EXTRA_PRAYER_NAME, prayerName)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val yesPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 10 + 1,
            yesIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // BELUM Action (triggers NO flow)
        val belumIntent = Intent(context, PrayerNotificationReceiver::class.java).apply {
            action = ACTION_PRAYER_NO
            putExtra(EXTRA_PRAYER_ID, prayerId)
            putExtra(EXTRA_PRAYER_NAME, prayerName)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val belumPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 10 + 2,
            belumIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_PRAYER_REMINDER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pengingat Salat $prayerName")
            .setContentText("Apakah kamu sudah selesai salat $prayerName?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.checkbox_on_background, "YES", yesPendingIntent)
            .addAction(android.R.drawable.ic_delete, "BELUM", belumPendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {}
    }

    fun showSnoozeReminderNotification(
        prayerId: String,
        prayerName: String,
        notificationId: Int
    ) {
        showPrayerIncomingNotification(
            prayerId = prayerId,
            prayerName = prayerName,
            timeFormatted = "Pengingat",
            notificationId = notificationId
        )
    }

    fun cancelNotification(notificationId: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (_: Exception) {}
    }
}
