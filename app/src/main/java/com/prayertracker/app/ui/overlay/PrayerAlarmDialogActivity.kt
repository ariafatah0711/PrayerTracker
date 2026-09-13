package com.prayertracker.app.ui.overlay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.prayertracker.app.PrayerTrackerApp
import com.prayertracker.app.scheduler.PrayerAlarmScheduler
import com.prayertracker.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PrayerAlarmDialogActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PRAYER_ID = "extra_prayer_id"
        const val EXTRA_PRAYER_NAME = "extra_prayer_name"
        const val EXTRA_TIME_FORMATTED = "extra_time_formatted"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_ALERT_TYPE = "extra_alert_type" // ENTRY, OTW, SNOOZE
        const val ACTION_DISMISS_OVERLAY = "com.prayertracker.app.ACTION_DISMISS_OVERLAY"

        @Volatile
        var isOverlayShowing: Boolean = false
            private set

        fun createIntent(
            context: Context,
            prayerId: String,
            prayerName: String,
            timeFormatted: String,
            notificationId: Int,
            alertType: String = "ENTRY"
        ): Intent {
            return Intent(context, PrayerAlarmDialogActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                putExtra(EXTRA_PRAYER_ID, prayerId)
                putExtra(EXTRA_PRAYER_NAME, prayerName)
                putExtra(EXTRA_TIME_FORMATTED, timeFormatted)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(EXTRA_ALERT_TYPE, alertType)
            }
        }
    }

    private var autoDismissJob: kotlinx.coroutines.Job? = null

    private val dismissReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val nId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, -1) ?: -1
            val curId = this@PrayerAlarmDialogActivity.intent.getIntExtra(EXTRA_NOTIFICATION_ID, 1001)
            if (nId == -1 || nId == curId) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isOverlayShowing = true

        // Daftarkan listener penutup otomatis jika user merespon langsung dari banner notifikasi atas
        val filter = android.content.IntentFilter(ACTION_DISMISS_OVERLAY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dismissReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dismissReceiver, filter)
        }

        // Lockscreen & Screen-on configurations
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prayerId = intent.getStringExtra(EXTRA_PRAYER_ID) ?: ""
        val prayerName = intent.getStringExtra(EXTRA_PRAYER_NAME) ?: "Salat"
        val timeFormatted = intent.getStringExtra(EXTRA_TIME_FORMATTED) ?: "Sekarang"
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 1001)
        val alertType = intent.getStringExtra(EXTRA_ALERT_TYPE) ?: "ENTRY"

        // Auto-dismiss timeout (45 detik):
        // Jika HP tergeletak di meja tanpa disentuh selama 45 detik,
        // matikan layar kembali untuk hemat baterai TANPA men-snooze 10 menit.
        // Nanti saat user menyalakan layar kembali, dialog akan otomatis muncul kembali.
        autoDismissJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(45000)
            if (!isFinishing && !isDestroyed) {
                handleAutoSleep()
            }
        }

        val app = applicationContext as PrayerTrackerApp

        setContent {
            val settings by app.settingsRepository.settingsFlow.collectAsState(initial = com.prayertracker.app.core.datastore.AppSettings())
            PrayerTrackerTheme {
                OverlayModalContent(
                    prayerName = prayerName,
                    timeFormatted = timeFormatted,
                    alertType = alertType,
                    otwIntervalMinutes = settings.otwIntervalMinutes,
                    noSnoozeIntervalMinutes = settings.noSnoozeIntervalMinutes,
                    onYes = { handleYes(prayerId, notificationId) },
                    onOtw = { handleOtw(prayerId, prayerName, notificationId) },
                    onNo = { handleNo(prayerId, prayerName, notificationId) }
                )
            }
        }
    }

    private fun handleAutoSleep() {
        autoDismissJob?.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!isFinishing) {
            finish()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Saat user menekan tombol Home atau gesture swipe keluar layar secara sengaja:
        // Tutup dialog sementara agar pengguna tidak terblokir untuk kebutuhan mendesak di HP,
        // TAPI JANGAN panggil handleNo() (tidak ada auto-snooze diam-diam).
        // Pengingat tetap standby di status bar, dan saat layar dimatikan & dinyalakan kembali,
        // dialog akan otomatis muncul kembali karena belum ada tombol yang dipilih.
        autoDismissJob?.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!isFinishing) {
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        autoDismissJob?.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Cukup tutup Activity agar hemat daya dan tidak menggantung di memori,
        // TANPA memicu handleNo() atau snooze otomatis.
        if (!isFinishing) {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Kunci tombol Back agar pengguna tidak menutup dialog secara tidak sengaja.
        // Pengguna diarahkan untuk memilih salah satu tombol respons secara sadar.
        Toast.makeText(this, "Silakan pilih: Sudah, OTW, atau Belum", Toast.LENGTH_SHORT).show()
    }

    private fun handleYes(prayerId: String, notificationId: Int) {
        autoDismissJob?.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PrayerAlarmScheduler.clearExplicitSnooze()
        val app = applicationContext as PrayerTrackerApp
        app.notificationHelper.cancelNotification(notificationId)
        app.alarmScheduler.cancelAllAlarmsForPrayer(notificationId)

        if (prayerId.startsWith("test_")) {
            Toast.makeText(this, "Uji Coba: Ditandai SUDAH SALAT (Notifikasi Dihapus)", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            app.repository.confirmPrayer(prayerId)
            launch(Dispatchers.Main) {
                Toast.makeText(this@PrayerAlarmDialogActivity, "Alhamdulillah! Salat berhasil dicatat.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun handleOtw(prayerId: String, prayerName: String, notificationId: Int) {
        autoDismissJob?.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = applicationContext as PrayerTrackerApp

        if (prayerId.startsWith("test_")) {
            lifecycleScope.launch(Dispatchers.IO) {
                val settings = app.settingsRepository.settingsFlow.first()
                PrayerAlarmScheduler.setExplicitSnooze(settings.otwIntervalMinutes)
                app.notificationHelper.showStandbyNotification(
                    prayerName = prayerName,
                    notificationId = notificationId,
                    delayMinutes = settings.otwIntervalMinutes,
                    isOtw = true,
                    prayerId = prayerId
                )
                app.alarmScheduler.scheduleOtwFollowUp(
                    prayerId = prayerId,
                    prayerName = prayerName,
                    delayMinutes = settings.otwIntervalMinutes,
                    notificationId = notificationId
                )
                launch(Dispatchers.Main) {
                    Toast.makeText(this@PrayerAlarmDialogActivity, "Uji Coba: Ditandai OTW — Notifikasi standby aktif (${settings.otwIntervalMinutes} menit)", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val settings = app.settingsRepository.settingsFlow.first()
            PrayerAlarmScheduler.setExplicitSnooze(settings.otwIntervalMinutes)
            app.repository.processOtw(prayerId)
            // Replace alarm notification with quiet standby notification
            app.notificationHelper.showStandbyNotification(
                prayerName = prayerName,
                notificationId = notificationId,
                delayMinutes = settings.otwIntervalMinutes,
                isOtw = true
            )
            app.alarmScheduler.scheduleOtwFollowUp(
                prayerId = prayerId,
                prayerName = prayerName,
                delayMinutes = settings.otwIntervalMinutes,
                notificationId = notificationId
            )
            launch(Dispatchers.Main) {
                Toast.makeText(this@PrayerAlarmDialogActivity, "Pengingat diatur ${settings.otwIntervalMinutes} menit lagi.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun handleNo(prayerId: String, prayerName: String, notificationId: Int) {
        autoDismissJob?.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = applicationContext as PrayerTrackerApp

        if (prayerId.startsWith("test_")) {
            lifecycleScope.launch(Dispatchers.IO) {
                val settings = app.settingsRepository.settingsFlow.first()
                PrayerAlarmScheduler.setExplicitSnooze(settings.noSnoozeIntervalMinutes)
                app.notificationHelper.showStandbyNotification(
                    prayerName = prayerName,
                    notificationId = notificationId,
                    delayMinutes = settings.noSnoozeIntervalMinutes,
                    isOtw = false,
                    prayerId = prayerId
                )
                app.alarmScheduler.scheduleNoSnooze(
                    prayerId = prayerId,
                    prayerName = prayerName,
                    delayMinutes = settings.noSnoozeIntervalMinutes,
                    notificationId = notificationId
                )
                launch(Dispatchers.Main) {
                    Toast.makeText(this@PrayerAlarmDialogActivity, "Uji Coba: Ditandai BELUM — Notifikasi standby aktif (${settings.noSnoozeIntervalMinutes} menit)", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val settings = app.settingsRepository.settingsFlow.first()
            PrayerAlarmScheduler.setExplicitSnooze(settings.noSnoozeIntervalMinutes)
            app.repository.processNo(prayerId)
            // Replace alarm notification with quiet standby notification
            app.notificationHelper.showStandbyNotification(
                prayerName = prayerName,
                notificationId = notificationId,
                delayMinutes = settings.noSnoozeIntervalMinutes,
                isOtw = false
            )
            app.alarmScheduler.scheduleNoSnooze(
                prayerId = prayerId,
                prayerName = prayerName,
                delayMinutes = settings.noSnoozeIntervalMinutes,
                notificationId = notificationId
            )
            launch(Dispatchers.Main) {
                Toast.makeText(this@PrayerAlarmDialogActivity, "Snooze diatur ${settings.noSnoozeIntervalMinutes} menit lagi.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isOverlayShowing = false
        autoDismissJob?.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            unregisterReceiver(dismissReceiver)
        } catch (_: Exception) {}
    }
}

@Composable
fun OverlayModalContent(
    prayerName: String,
    timeFormatted: String,
    alertType: String,
    otwIntervalMinutes: Int = 3,
    noSnoozeIntervalMinutes: Int = 10,
    onYes: () -> Unit,
    onOtw: () -> Unit,
    onNo: () -> Unit
) {
    // Animasi denyut halus pada ikon adzan
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // Backdrop transparan gelap (TIDAK BISA di-dismiss sembarangan, wajib klik salah satu tombol aksi)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = true,
            enter = scaleIn(initialScale = 0.88f) + fadeIn()
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 330.dp)
                    .fillMaxWidth()
                    .clickable(enabled = false) { /* mencegah klik tembus */ },
                color = SurfaceDark,
                tonalElevation = 10.dp,
                border = BorderStroke(1.2.dp, EmeraldLight.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. Tag Badge Atas (Centered)
                    Surface(
                        color = EmeraldContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            val badgeTitle = when (alertType) {
                                "OTW" -> "🚶 KONFIRMASI OTW"
                                "SNOOZE" -> "⏰ PENGINGAT SNOOZE"
                                else -> "🕌 WAKTU SALAT TIBA"
                            }
                            Text(
                                text = badgeTitle,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldLight,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 2. Icon Lingkaran dengan Denyut Cahaya (Centered)
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        EmeraldLight.copy(alpha = 0.35f),
                                        EmeraldContainer.copy(alpha = 0.15f),
                                        Color.Transparent
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(EmeraldPrimary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (alertType == "OTW") Icons.Default.DirectionsWalk else Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3. Nama Salat (Centered & Proporsional)
                    Text(
                        text = prayerName.uppercase(),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        ),
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // 4. Jadwal Waktu Salat (Centered Pill)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = AmberGold,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = timeFormatted,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 5. Pesan Motivasi Ringkas
                    Text(
                        text = if (alertType == "OTW")
                            "Segera selesaikan wudhu dan salatmu agar ibadah tepat waktu."
                        else
                            "\"Amalan yang paling dicintai Allah adalah salat tepat pada waktunya.\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // 6. Tiga Tombol Aksi Wajib (Centered, Rapi, Simetris)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        // Tombol 1: SUDAH SALAT
                        Button(
                            onClick = onYes,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StatusCompleted,
                                contentColor = Color.White
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("SUDAH SALAT", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        // Tombol 2: OTW / SIAP-SIAP
                        Button(
                            onClick = onOtw,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StatusOtw,
                                contentColor = Color.White
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                        ) {
                            Icon(Icons.Default.DirectionsWalk, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            val otwLabel = if (alertType == "OTW") "MASIH OTW ($otwIntervalMinutes Mnt)" else "OTW / SIAP-SIAP ($otwIntervalMinutes Mnt)"
                            Text(otwLabel, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        // Tombol 3: BELUM / SNOOZE
                        OutlinedButton(
                            onClick = onNo,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, SurfaceCardBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                        ) {
                            Icon(Icons.Default.Snooze, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSecondary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("BELUM / TUNDA ($noSnoozeIntervalMinutes Mnt)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
