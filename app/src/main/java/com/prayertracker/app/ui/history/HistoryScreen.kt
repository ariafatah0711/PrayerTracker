package com.prayertracker.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                    .padding(horizontal = 16.dp)
            ) {
                // Header
                Column(modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)) {
                    Text(
                        text = "Riwayat Salat",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Catatan lengkap seluruh aktivitas dan status salat kamu",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }

                // Filter Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 14.dp)
                ) {
                    items(HistoryFilter.entries) { filter ->
                        val isSelected = state.selectedFilter == filter
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.onFilterSelected(filter) },
                            label = { Text(filter.displayName) },
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
                            shape = RoundedCornerShape(10.dp)
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
                            text = "Tidak ada riwayat untuk filter ini.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextMuted
                        )
                    }
                } else {
                    val grouped = state.filteredPrayers.groupBy { it.prayerDate }
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        grouped.forEach { (date, itemsForDate) ->
                            item {
                                Text(
                                    text = date,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AmberGold,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                            items(itemsForDate, key = { it.id }) { prayer ->
                                HistoryPrayerCard(prayer = prayer)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryPrayerCard(prayer: PrayerItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, SurfaceCardBorder, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
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
                        modifier = Modifier.size(18.dp)
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
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (prayer.formattedCompletedTime != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Pukul ${prayer.formattedCompletedTime}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
        }
    }
}
