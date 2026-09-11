package com.prayertracker.app.ui.statistics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prayertracker.app.ui.theme.*

@Composable
fun StatisticsScreen(
    viewModel: StatisticsViewModel,
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
            val stats = state.stats
            val totalEvaluated = stats.completedCount + stats.missedCount + stats.qadhaCompletedCount
            val completedPct = if (totalEvaluated > 0) ((stats.completedCount.toDouble() / totalEvaluated) * 100).toInt() else 100
            val qadhaPct = if (totalEvaluated > 0) ((stats.qadhaCompletedCount.toDouble() / totalEvaluated) * 100).toInt() else 0
            val missedPct = if (totalEvaluated > 0) (100 - completedPct - qadhaPct).coerceAtLeast(0) else 0

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
            ) {
                // Header
                item {
                    Column(modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
                        Text(
                            text = "Statistik Salat",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Kedisiplinan dan riwayat ketepatan waktu ibadah kamu",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                // Main Completion KPI Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        border = BorderStroke(
                            1.dp,
                            Brush.horizontalGradient(
                                listOf(
                                    EmeraldLight.copy(alpha = 0.6f),
                                    AmberGold.copy(alpha = 0.5f)
                                )
                            )
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            EmeraldContainer.copy(alpha = 0.35f),
                                            Color.Transparent
                                        )
                                    )
                                )
                                .padding(18.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Surface(
                                            color = EmeraldDark.copy(alpha = 0.4f),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, EmeraldLight.copy(alpha = 0.3f))
                                        ) {
                                            Text(
                                                text = "TINGKAT KETAATAN",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = EmeraldLight,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 0.8.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                            text = "${stats.completionPercentage}%",
                                            style = MaterialTheme.typography.headlineLarge.copy(
                                                fontSize = 42.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = (-1).sp
                                            ),
                                            color = TextPrimary
                                        )

                                        Spacer(modifier = Modifier.height(2.dp))

                                        val assessmentText = when {
                                            stats.completionPercentage >= 90 -> "🌟 Sangat Istiqomah"
                                            stats.completionPercentage >= 75 -> "✨ Disiplin & Tertib"
                                            stats.completionPercentage >= 50 -> "📈 Cukup Baik"
                                            else -> "⚡ Perlu Ditingkatkan"
                                        }
                                        val assessmentColor = when {
                                            stats.completionPercentage >= 75 -> EmeraldLight
                                            stats.completionPercentage >= 50 -> AmberGold
                                            else -> StatusMissed
                                        }
                                        Text(
                                            text = assessmentText,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = assessmentColor,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    // Circular Gauge Ring
                                    Box(
                                        modifier = Modifier.size(76.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            progress = { (stats.completionPercentage / 100f).coerceIn(0f, 1f) },
                                            strokeWidth = 7.dp,
                                            color = EmeraldLight,
                                            trackColor = SurfaceDark,
                                            modifier = Modifier.size(76.dp)
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = null,
                                            tint = AmberGold,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Sleek Progress bar
                                LinearProgressIndicator(
                                    progress = { (stats.completionPercentage / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = EmeraldLight,
                                    trackColor = SurfaceDark
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                // Bottom summary chips
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Total $totalEvaluated salat tercatat",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                    Text(
                                        text = "${stats.completedCount} Tepat Waktu",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = EmeraldLight,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                // Section Title: Metrik Utama
                item {
                    Text(
                        text = "Ringkasan Performa",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                // Grid Row 1
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatMetricCard(
                            title = "Tepat Waktu",
                            value = "${stats.completedCount}",
                            sub = "Dikerjakan awal waktu",
                            icon = Icons.Default.CheckCircle,
                            iconColor = StatusCompleted,
                            bgColor = StatusCompletedBg.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f)
                        )
                        StatMetricCard(
                            title = "Terlewat",
                            value = "${stats.missedCount}",
                            sub = "Lewat batas akhir",
                            icon = Icons.Default.Close,
                            iconColor = StatusMissed,
                            bgColor = StatusMissedBg.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Grid Row 2
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatMetricCard(
                            title = "Diqadha",
                            value = "${stats.qadhaCompletedCount}",
                            sub = "Kewajiban tertunai",
                            icon = Icons.Default.Restore,
                            iconColor = StatusOtw,
                            bgColor = StatusOtwBg.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f)
                        )
                        StatMetricCard(
                            title = "Streak Disiplin",
                            value = "${stats.streakDays} Hari",
                            sub = "Tanpa terlewat",
                            icon = Icons.Default.Whatshot,
                            iconColor = AmberGold,
                            bgColor = AmberContainer.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Distribution Breakdown Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        border = BorderStroke(1.dp, SurfaceCardBorder)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "Distribusi Status Ibadah",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // Segmented Bar
                            if (totalEvaluated > 0) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                ) {
                                    if (completedPct > 0) {
                                        Box(
                                            modifier = Modifier
                                                .weight(completedPct.toFloat())
                                                .fillMaxHeight()
                                                .background(StatusCompleted)
                                        )
                                    }
                                    if (qadhaPct > 0) {
                                        Box(
                                            modifier = Modifier
                                                .weight(qadhaPct.toFloat())
                                                .fillMaxHeight()
                                                .background(StatusOtw)
                                        )
                                    }
                                    if (missedPct > 0) {
                                        Box(
                                            modifier = Modifier
                                                .weight(missedPct.toFloat())
                                                .fillMaxHeight()
                                                .background(StatusMissed)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                            }

                            // Detail Rows
                            DistributionRow(
                                label = "Tepat Waktu",
                                count = stats.completedCount,
                                percentage = completedPct,
                                color = StatusCompleted
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            DistributionRow(
                                label = "Berhasil Diqadha",
                                count = stats.qadhaCompletedCount,
                                percentage = qadhaPct,
                                color = StatusOtw
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            DistributionRow(
                                label = "Terlewat (Belum Qadha)",
                                count = stats.missedCount,
                                percentage = missedPct,
                                color = StatusMissed
                            )
                        }
                    }
                }

                // Informational Card (Batas Waktu & Keamanan Data)
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = SurfaceDark.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, SurfaceCardBorder.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = EmeraldLight,
                                modifier = Modifier
                                    .size(20.dp)
                                    .padding(top = 2.dp)
                            )
                            Column {
                                Text(
                                    text = "Tentang Batas Waktu & Ketaatan",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Salat terhitung Tepat Waktu jika ditandai sebelum masuk batas akhir waktu salat berikutnya. Data riwayat lampau tersimpan aman secara permanen dan tidak terpengaruh perubahan lokasi atau metode hisab.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatMetricCard(
    title: String,
    value: String,
    sub: String,
    icon: ImageVector,
    iconColor: Color,
    bgColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, SurfaceCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(bgColor, shape = RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = TextPrimary,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = TextMuted,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DistributionRow(
    label: String,
    count: Int,
    percentage: Int,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Text(
            text = "$count ($percentage%)",
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}
