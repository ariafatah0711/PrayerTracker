package com.prayertracker.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.prayertracker.app.ui.dashboard.DashboardScreen
import com.prayertracker.app.ui.dashboard.DashboardViewModel
import com.prayertracker.app.ui.history.HistoryScreen
import com.prayertracker.app.ui.history.HistoryViewModel
import com.prayertracker.app.ui.navigation.Screen
import com.prayertracker.app.ui.qadha.QadhaScreen
import com.prayertracker.app.ui.qadha.QadhaViewModel
import com.prayertracker.app.ui.settings.SettingsScreen
import com.prayertracker.app.ui.settings.SettingsViewModel
import com.prayertracker.app.ui.statistics.StatisticsScreen
import com.prayertracker.app.ui.statistics.StatisticsViewModel
import com.prayertracker.app.ui.theme.*
import com.prayertracker.app.ui.onboarding.OnboardingScreen
import com.prayertracker.app.ui.onboarding.OnboardingViewModel
import com.prayertracker.app.ui.qadha.QadhaUiState
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notification permission result handled silently or gracefully
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestNotificationPermission()

        val app = application as PrayerTrackerApp

        setContent {
            PrayerTrackerTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                // Factory instances
                val dashboardViewModel: DashboardViewModel = viewModel(
                    factory = DashboardViewModel.Factory(
                        app.getTodayPrayersUseCase,
                        app.confirmPrayerUseCase,
                        app.processNoUseCase,
                        app.processOtwUseCase,
                        app.markPrayerMissedUseCase,
                        app.reconcileMissedPrayersUseCase,
                        app.settingsRepository,
                        app.alarmScheduler,
                        app.notificationHelper
                    )
                )

                val qadhaViewModel: QadhaViewModel = viewModel(
                    factory = QadhaViewModel.Factory(
                        app.getQadhaListUseCase,
                        app.performQadhaUseCase,
                        app.reconcileMissedPrayersUseCase,
                        app.syncCoordinator
                    )
                )

                val historyViewModel: HistoryViewModel = viewModel(
                    factory = HistoryViewModel.Factory(app.getHistoryUseCase)
                )

                val statisticsViewModel: StatisticsViewModel = viewModel(
                    factory = StatisticsViewModel.Factory(app.getStatisticsUseCase)
                )

                val settingsViewModel: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(
                        app.settingsRepository,
                        app.googleAuthManager,
                        app.syncCoordinator,
                        app.notificationHelper,
                        app.resetAllDataUseCase,
                        app.localBackupManager
                    )
                )

                // Otomatis segarkan data di seluruh layar saat sync / restore / pull selesai
                LaunchedEffect(Unit) {
                    app.syncCoordinator.dataRefreshEvent.collect {
                        dashboardViewModel.loadData()
                        qadhaViewModel.loadData()
                        historyViewModel.loadHistory()
                        statisticsViewModel.loadStatistics()
                    }
                }

                // Otomatis segarkan data saat berpindah tab
                LaunchedEffect(currentRoute) {
                    when (currentRoute) {
                        Screen.Dashboard.route -> dashboardViewModel.loadData()
                        Screen.Qadha.route -> qadhaViewModel.loadData()
                        Screen.History.route -> historyViewModel.loadHistory()
                        Screen.Statistics.route -> statisticsViewModel.loadStatistics()
                    }
                }

                val isSettingsLoaded by settingsViewModel.isLoaded.collectAsState()
                val settings by settingsViewModel.settings.collectAsState()

                // Tunggu sampai settings benar-benar terpopulasi dari DataStore
                val isReallyReady = isSettingsLoaded

                val unpaidQadhaCount by remember(qadhaViewModel) {
                    qadhaViewModel.uiState.map { state: QadhaUiState -> state.missedPrayers.size }
                }.collectAsState(initial = 0)

                if (!isReallyReady) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(com.prayertracker.app.ui.theme.BackgroundDark),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = com.prayertracker.app.ui.theme.EmeraldLight)
                    }
                } else if (!settings.isOnboardingCompleted) {
                    val onboardingViewModel: OnboardingViewModel = viewModel(
                        factory = OnboardingViewModel.Factory(
                            app.settingsRepository,
                            app.getTodayPrayersUseCase,
                            app.confirmPrayerUseCase,
                            app.markPrayerMissedUseCase,
                            app.alarmScheduler
                        )
                    )
                    OnboardingScreen(
                        viewModel = onboardingViewModel,
                        onFinished = {
                            dashboardViewModel.loadData()
                        }
                    )
                } else {
                    Scaffold(
                        containerColor = com.prayertracker.app.ui.theme.BackgroundDark,
                        bottomBar = {
                            val bottomRoute = navController.currentBackStackEntryAsState().value?.destination?.route
                            NavigationBar(
                                containerColor = com.prayertracker.app.ui.theme.SurfaceDark,
                                contentColor = com.prayertracker.app.ui.theme.TextPrimary
                            ) {
                                Screen.items.filterNotNull().forEach { screen ->
                                    val route = screen.route
                                    val isSelected = bottomRoute == route
                                    NavigationBarItem(
                                        selected = isSelected,
                                        onClick = {
                                            if (bottomRoute != route) {
                                                navController.navigate(route) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        },
                                        icon = {
                                            if (screen == Screen.Qadha && unpaidQadhaCount > 0) {
                                                BadgedBox(badge = {
                                                    Badge(
                                                        containerColor = com.prayertracker.app.ui.theme.StatusMissed,
                                                        contentColor = Color.White
                                                    ) {
                                                        Text("$unpaidQadhaCount")
                                                    }
                                                }) {
                                                    Icon(imageVector = screen.icon, contentDescription = screen.title)
                                                }
                                            } else {
                                                Icon(imageVector = screen.icon, contentDescription = screen.title)
                                            }
                                        },
                                        label = { Text(screen.title) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = com.prayertracker.app.ui.theme.EmeraldLight,
                                            selectedTextColor = com.prayertracker.app.ui.theme.EmeraldLight,
                                            unselectedIconColor = com.prayertracker.app.ui.theme.TextSecondary,
                                            unselectedTextColor = com.prayertracker.app.ui.theme.TextSecondary,
                                            indicatorColor = com.prayertracker.app.ui.theme.EmeraldContainer
                                        )
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        NavHost(
                            navController = navController,
                            startDestination = Screen.Dashboard.route,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            composable(Screen.Dashboard.route) {
                                DashboardScreen(viewModel = dashboardViewModel)
                            }
                            composable(Screen.Qadha.route) {
                                QadhaScreen(viewModel = qadhaViewModel)
                            }
                            composable(Screen.History.route) {
                                HistoryScreen(viewModel = historyViewModel)
                            }
                            composable(Screen.Statistics.route) {
                                StatisticsScreen(viewModel = statisticsViewModel)
                            }
                            composable(Screen.Settings.route) {
                                SettingsScreen(viewModel = settingsViewModel)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionStatus = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
