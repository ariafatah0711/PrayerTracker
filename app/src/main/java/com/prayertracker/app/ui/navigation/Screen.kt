package com.prayertracker.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Dashboard : Screen("dashboard", "Beranda", Icons.Default.Home)
    data object Qadha : Screen("qadha", "Qadha", Icons.Default.CheckCircle)
    data object History : Screen("history", "Riwayat", Icons.AutoMirrored.Filled.List)
    data object Statistics : Screen("statistics", "Statistik", Icons.Default.BarChart)
    data object Settings : Screen("settings", "Pengaturan", Icons.Default.Settings)

    companion object {
        val items: List<Screen>
            get() = listOf(Dashboard, Qadha, History, Statistics, Settings)
    }
}
