package com.prayertracker.app.ui.dashboard

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prayertracker.app.core.model.PrayerStatus
import com.prayertracker.app.domain.model.PrayerItem
import com.prayertracker.app.ui.theme.*

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = BackgroundDark,
        modifier = modifier
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = EmeraldLight)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                // Header: Location & Date
                item {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = "Lokasi",
                                tint = EmeraldLight,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = state.cityName,
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = state.formattedDate,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }

                // Hero Card: Next Prayer
                item {
                    state.nextPrayer?.let { next ->
                        NextPrayerHeroCard(
                            prayer = next,
                            remainingTimeText = state.remainingTimeText
                        )
                    }
                }

                // System & Alarm Health Status Banner (Peace of mind indicator)
                item {
                    SystemAlarmStatusBanner(
                        isArmed = state.isAlarmArmed,
                        detailText = state.activeAlarmDetail
                    )
                }

                // Section Title
                item {
                    Text(
                        text = "Jadwal Salat Hari Ini",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // List of 5 Prayers
                items(state.todayPrayers, key = { it.id }) { prayer ->
                    PrayerCard(
                        prayer = prayer,
                        onYes = { viewModel.onYesClicked(prayer.id) },
                        onNo = { viewModel.onNoClicked(prayer.id) },
                        onOtw = { viewModel.onOtwClicked(prayer.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun SystemAlarmStatusBanner(
    isArmed: Boolean,
    detailText: String
) {
    val bannerShape = RoundedCornerShape(14.dp)
    val borderColor = if (isArmed) StatusCompleted.copy(alpha = 0.4f) else SurfaceCardBorder

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = bannerShape,
        border = BorderStroke(1.dp, borderColor),
        colors = CardDefaults.cardColors(
            containerColor = if (isArmed) StatusCompletedBg.copy(alpha = 0.25f) else SurfaceDark
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isArmed) StatusCompleted else TextMuted)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isArmed) "Pengingat Aktif di Sistem (Hemat Daya)" else "Pengingat Selesai",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isArmed) StatusCompleted else TextSecondary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1
                )
            }
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = if (isArmed) StatusCompleted else TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun NextPrayerHeroCard(
    prayer: PrayerItem,
    remainingTimeText: String
) {
    val borderBrush = remember { Brush.linearGradient(listOf(EmeraldPrimary, AmberGold)) }
    val bgBrush = remember {
        Brush.radialGradient(
            colors = listOf(EmeraldContainer.copy(alpha = 0.55f), SurfaceDark),
            radius = 600f
        )
    }
    val cardShape = RoundedCornerShape(20.dp)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        border = BorderStroke(1.5.dp, borderBrush),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgBrush)
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isOngoing = prayer.scheduledEpoch <= System.currentTimeMillis() &&
                            System.currentTimeMillis() < prayer.endEpoch &&
                            (prayer.status == PrayerStatus.PENDING || prayer.status == PrayerStatus.OTW)

                    Text(
                        text = if (isOngoing) "SEDANG BERLANGSUNG" else "SALAT BERIKUTNYA",
                        style = MaterialTheme.typography.labelLarge,
                        color = AmberGold,
                        letterSpacing = 1.2.sp
                    )

                    if (remainingTimeText.isNotBlank()) {
                        Surface(
                            shape = CircleShape,
                            color = EmeraldContainer,
                            border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldLight)
                        ) {
                            Text(
                                text = remainingTimeText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = EmeraldLight,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = prayer.effectiveDisplayName,
                            style = MaterialTheme.typography.headlineLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Batas akhir: ${prayer.formattedEndTime}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }

                    Text(
                        text = prayer.formattedScheduledTime,
                        style = MaterialTheme.typography.headlineLarge,
                        color = EmeraldLight,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun PrayerCard(
    prayer: PrayerItem,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onOtw: () -> Unit
) {
    val now = System.currentTimeMillis()
    val isCurrentlyActive = now in prayer.scheduledEpoch..prayer.endEpoch
    val canTakeDirectAction = (prayer.status == PrayerStatus.PENDING && isCurrentlyActive) || prayer.status == PrayerStatus.OTW
    val cardShape = RoundedCornerShape(16.dp)

    val borderColor = when (prayer.status) {
        PrayerStatus.COMPLETED -> StatusCompleted.copy(alpha = 0.4f)
        PrayerStatus.OTW -> StatusOtw.copy(alpha = 0.6f)
        PrayerStatus.MISSED -> StatusMissed.copy(alpha = 0.4f)
        PrayerStatus.PENDING -> if (isCurrentlyActive) AmberGold.copy(alpha = 0.6f) else SurfaceCardBorder
        PrayerStatus.QADHA_COMPLETED -> EmeraldLight.copy(alpha = 0.3f)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        border = BorderStroke(1.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                when (prayer.status) {
                                    PrayerStatus.COMPLETED -> StatusCompletedBg
                                    PrayerStatus.OTW -> StatusOtwBg
                                    PrayerStatus.MISSED -> StatusMissedBg
                                    PrayerStatus.PENDING -> if (isCurrentlyActive) StatusPendingBg else SurfaceDark
                                    PrayerStatus.QADHA_COMPLETED -> StatusCompletedBg
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (prayer.status) {
                                PrayerStatus.COMPLETED, PrayerStatus.QADHA_COMPLETED -> Icons.Default.Check
                                PrayerStatus.MISSED -> Icons.Default.Close
                                else -> Icons.Default.Schedule
                            },
                            contentDescription = null,
                            tint = when (prayer.status) {
                                PrayerStatus.COMPLETED, PrayerStatus.QADHA_COMPLETED -> StatusCompleted
                                PrayerStatus.OTW -> StatusOtw
                                PrayerStatus.MISSED -> StatusMissed
                                PrayerStatus.PENDING -> if (isCurrentlyActive) AmberGold else TextSecondary
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Text(
                            text = prayer.effectiveDisplayName,
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Jadwal: ${prayer.formattedScheduledTime}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }

                // Status Badge
                StatusBadge(prayer = prayer, isCurrentlyActive = isCurrentlyActive)
            }

            // Quick Actions if active
            AnimatedVisibility(
                visible = canTakeDirectAction,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 14.dp)) {
                    Divider(color = SurfaceCardBorder, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    if (prayer.status == PrayerStatus.OTW) {
                        Text(
                            text = "Apakah kamu sudah selesai salat?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StatusOtw,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onYes,
                                colors = ButtonDefaults.buttonColors(containerColor = StatusCompleted),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Sudah (YES)", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = onNo,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Belum", color = TextPrimary)
                            }
                        }
                    } else if (prayer.status == PrayerStatus.PENDING && isCurrentlyActive) {
                        Text(
                            text = "Sudah salat?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AmberGold,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onYes,
                                colors = ButtonDefaults.buttonColors(containerColor = StatusCompleted),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("YES", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = onNo,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("NO", color = TextPrimary)
                            }
                            Button(
                                onClick = onOtw,
                                colors = ButtonDefaults.buttonColors(containerColor = StatusOtw),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("OTW", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(
    prayer: PrayerItem,
    isCurrentlyActive: Boolean
) {
    val (text, color, bgColor) = when (prayer.status) {
        PrayerStatus.COMPLETED -> Triple(
            "Selesai",
            StatusCompleted,
            StatusCompletedBg
        )
        PrayerStatus.OTW -> Triple("OTW / Bersiap", StatusOtw, StatusOtwBg)
        PrayerStatus.MISSED -> Triple("Terlewat", StatusMissed, StatusMissedBg)
        PrayerStatus.PENDING -> if (isCurrentlyActive) {
            Triple("Waktunya Salat", AmberGold, StatusPendingBg)
        } else {
            Triple("Menunggu", TextSecondary, SurfaceDark)
        }
        PrayerStatus.QADHA_COMPLETED -> Triple("Qadha Selesai", StatusCompleted, StatusCompletedBg)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}
