package com.prayertracker.app.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prayertracker.app.core.model.PrayerStatus
import com.prayertracker.app.domain.model.PrayerItem
import com.prayertracker.app.ui.theme.*

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 14.dp)
            ) {
                // Header
                Column(modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)) {
                    Text(
                        text = "Riwayat Salat",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Catatan kedisiplinan dan pelunasan salat harian",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                // Segmented Time Range Selector (7 Hari | 30 Hari | Semua)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceDark)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    HistoryTimeRange.entries.forEach { range ->
                        val isSelected = state.selectedTimeRange == range
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.onTimeRangeSelected(range) },
                            color = if (isSelected) EmeraldPrimary else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = range.displayName,
                                modifier = Modifier.padding(vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else TextSecondary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Status Filter Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    items(HistoryFilter.entries) { filter ->
                        val isSelected = state.selectedFilter == filter
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.onFilterSelected(filter) },
                            label = { Text(filter.displayName, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EmeraldContainer,
                                selectedLabelColor = EmeraldLight,
                                containerColor = SurfaceCard,
                                labelColor = TextSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = if (isSelected) EmeraldLight else SurfaceCardBorder
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                if (state.filteredPrayers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Tidak ada riwayat untuk periode ini.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                } else {
                    val grouped = state.filteredPrayers.groupBy { it.prayerDate }
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        grouped.forEach { (date, itemsForDate) ->
                            item(key = "header_$date") {
                                Text(
                                    text = formatHistoryDate(date),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = AmberGold,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                                )
                            }
                            items(itemsForDate, key = { it.id }) { prayer ->
                                HistoryPrayerCard(prayer = prayer)
                            }
                        }

                        // Pagination Button or Completion Footer
                        if (state.hasMoreDays) {
                            item(key = "load_more_btn") {
                                val remaining = state.totalAvailableDays - state.visibleDaysCount
                                OutlinedButton(
                                    onClick = { viewModel.loadMoreDays() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldLight),
                                    border = BorderStroke(1.dp, EmeraldLight.copy(alpha = 0.6f))
                                ) {
                                    Icon(Icons.Default.ExpandMore, contentDescription = null, tint = EmeraldLight, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Muat 7 Hari Sebelumnya (${remaining.coerceAtLeast(1)} hari lagi)",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = EmeraldLight
                                    )
                                }
                            }
                        } else if (grouped.isNotEmpty()) {
                            item(key = "all_loaded_msg") {
                                Text(
                                    text = "✓ Seluruh riwayat (${grouped.size} hari) telah ditampilkan",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatHistoryDate(dateStr: String): String {
    return try {
        val date = java.time.LocalDate.parse(dateStr)
        val today = java.time.LocalDate.now()
        val yesterday = today.minusDays(1)
        val formatter = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale("id", "ID"))
        val dayName = when (date.dayOfWeek) {
            java.time.DayOfWeek.MONDAY -> "Senin"
            java.time.DayOfWeek.TUESDAY -> "Selasa"
            java.time.DayOfWeek.WEDNESDAY -> "Rabu"
            java.time.DayOfWeek.THURSDAY -> "Kamis"
            java.time.DayOfWeek.FRIDAY -> "Jumat"
            java.time.DayOfWeek.SATURDAY -> "Sabtu"
            java.time.DayOfWeek.SUNDAY -> "Minggu"
            else -> ""
        }
        when (date) {
            today -> "Hari Ini • $dayName, ${date.format(formatter)}"
            yesterday -> "Kemarin • $dayName, ${date.format(formatter)}"
            else -> "$dayName, ${date.format(formatter)}"
        }
    } catch (_: Exception) {
        dateStr
    }
}

@Composable
fun HistoryPrayerCard(prayer: PrayerItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, SurfaceCardBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            when (prayer.status) {
                                PrayerStatus.COMPLETED, PrayerStatus.QADHA_COMPLETED -> StatusCompletedBg
                                PrayerStatus.MISSED -> StatusMissedBg
                                else -> SurfaceDark
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
                            PrayerStatus.MISSED -> StatusMissed
                            else -> TextSecondary
                        },
                        modifier = Modifier.size(16.dp)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = prayer.effectiveDisplayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Jadwal: ${prayer.formattedScheduledTime}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (prayer.status) {
                        PrayerStatus.COMPLETED, PrayerStatus.QADHA_COMPLETED -> StatusCompletedBg
                        PrayerStatus.MISSED -> StatusMissedBg
                        PrayerStatus.OTW -> StatusOtwBg
                        PrayerStatus.PENDING -> StatusPendingBg
                    }
                ) {
                    Text(
                        text = when (prayer.status) {
                            PrayerStatus.COMPLETED -> "Selesai"
                            PrayerStatus.QADHA_COMPLETED -> "Sudah Qadha"
                            PrayerStatus.MISSED -> "Terlewat"
                            PrayerStatus.OTW -> "OTW"
                            PrayerStatus.PENDING -> "Menunggu"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (prayer.status) {
                            PrayerStatus.COMPLETED, PrayerStatus.QADHA_COMPLETED -> StatusCompleted
                            PrayerStatus.MISSED -> StatusMissed
                            PrayerStatus.OTW -> StatusOtw
                            PrayerStatus.PENDING -> AmberGold
                        },
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                if (prayer.formattedCompletedTime != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Pukul ${prayer.formattedCompletedTime}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }
        }
    }
}
