package com.prayertracker.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Beranda", Icons.Default.Home)
    object Qadha : Screen("qadha", "Qadha", Icons.Default.CheckCircle)
    object History : Screen("history", "Riwayat", Icons.AutoMirrored.Filled.List)
    object Statistics : Screen("statistics", "Statistik", Icons.Default.BarChart)
    object Settings : Screen("settings", "Pengaturan", Icons.Default.Settings)

    companion object {
        val items = listOf(Dashboard, Qadha, History, Statistics, Settings)
    }
}
