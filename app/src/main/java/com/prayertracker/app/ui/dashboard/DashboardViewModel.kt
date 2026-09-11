package com.prayertracker.app.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prayertracker.app.core.datastore.AppSettingsRepository
import com.prayertracker.app.core.model.PrayerStatus
import com.prayertracker.app.domain.model.PrayerItem
import com.prayertracker.app.domain.usecase.*
import com.prayertracker.app.scheduler.PrayerAlarmScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DashboardUiState(
    val todayPrayers: List<PrayerItem> = emptyList(),
    val nextPrayer: PrayerItem? = null,
    val remainingTimeText: String = "",
    val formattedDate: String = "",
    val cityName: String = "Jakarta",
    val isLoading: Boolean = true,
    val isAlarmArmed: Boolean = true,
    val activeAlarmDetail: String = "Alarm sistem Android aktif & siap membunyikan notifikasi"
)

class DashboardViewModel(
    private val getTodayPrayersUseCase: GetTodayPrayersUseCase,
    private val confirmPrayerUseCase: ConfirmPrayerUseCase,
    private val processNoUseCase: ProcessNoUseCase,
    private val processOtwUseCase: ProcessOtwUseCase,
    private val markPrayerMissedUseCase: MarkPrayerMissedUseCase,
    private val reconcileMissedPrayersUseCase: ReconcileMissedPrayersUseCase,
    private val settingsRepository: AppSettingsRepository,
    private val alarmScheduler: PrayerAlarmScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val dateFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale("id", "ID"))
    private var observeJob: kotlinx.coroutines.Job? = null

    init {
        loadData()
        startCountdownTimer()
    }

    fun loadData() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }

            val settings = settingsRepository.settingsFlow.first()
            val today = LocalDate.now()

            // Reconcile missed prayers and generate today's prayers on IO
            val initialPrayers = getTodayPrayersUseCase.ensureAndGet(
                date = today,
                latitude = settings.latitude,
                longitude = settings.longitude,
                zoneId = ZoneId.systemDefault(),
                installedAtEpoch = settings.installedAtEpoch
            )

            // Reschedule any pending alarms for today's prayers
            initialPrayers.forEachIndexed { index, prayer ->
                if (prayer.status == PrayerStatus.PENDING && prayer.scheduledEpoch > System.currentTimeMillis()) {
                    alarmScheduler.schedulePrayerEntry(
                        prayerId = prayer.id,
                        prayerName = prayer.effectiveDisplayName,
                        triggerEpoch = prayer.scheduledEpoch,
                        notificationId = 1000 + index
                    )
                }
            }

            // Observe live changes from Room DB
            getTodayPrayersUseCase.observeToday(today).collectLatest { prayers ->
                val next = calculateNextPrayer(prayers)
                val alarmDetail = if (next != null) {
                    "Alarm ${next.effectiveDisplayName} (${next.formattedScheduledTime}) aktif di sistem Android"
                } else {
                    "Semua salat hari ini telah selesai / terlewat"
                }

                _uiState.update { current ->
                    current.copy(
                        todayPrayers = prayers,
                        nextPrayer = next,
                        remainingTimeText = formatRemainingTime(next?.scheduledEpoch),
                        formattedDate = today.format(dateFormatter),
                        cityName = settings.cityName,
                        isAlarmArmed = next != null,
                        activeAlarmDetail = alarmDetail,
                        isLoading = false
                    )
                }
            }
        }
    }

    val supportedCountries = com.prayertracker.app.domain.model.supportedCountries
    val allCities = com.prayertracker.app.domain.model.allPredefinedCities
    val availableCities = com.prayertracker.app.domain.model.defaultIndonesianCities

    fun updateCityLocation(city: com.prayertracker.app.domain.model.PredefinedCity) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.updateLocation(city.name, city.lat, city.lng)
            loadData()
        }
    }

    fun updateCustomLocation(cityName: String, lat: Double, lng: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.updateLocation(cityName, lat, lng)
            loadData()
        }
    }

    fun detectGpsLocation(context: Context, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = com.prayertracker.app.core.location.LocationHelper.getCurrentDeviceLocation(context)
            if (res.isSuccess) {
                val (city, lat, lng) = res.getOrThrow()
                settingsRepository.updateLocation(city, lat, lng)
                loadData()
                withContext(Dispatchers.Main) {
                    onResult(true, "Lokasi berhasil terdeteksi: $city")
                }
            } else {
                withContext(Dispatchers.Main) {
                    onResult(false, res.exceptionOrNull()?.message ?: "Gagal mendeteksi lokasi GPS")
                }
            }
        }
    }

    private fun startCountdownTimer() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30000) // update every 30 seconds
                val settings = settingsRepository.settingsFlow.first()
                // Auto reconcile when times transition
                reconcileMissedPrayersUseCase(settings.installedAtEpoch)

                val next = calculateNextPrayer(_uiState.value.todayPrayers)
                _uiState.update { current ->
                    current.copy(
                        nextPrayer = next,
                        remainingTimeText = formatRemainingTime(next?.scheduledEpoch)
                    )
                }
            }
        }
    }

    private fun calculateNextPrayer(prayers: List<PrayerItem>): PrayerItem? {
        val now = System.currentTimeMillis()
        return prayers.firstOrNull { it.scheduledEpoch > now }
            ?: prayers.firstOrNull { it.status == PrayerStatus.PENDING || it.status == PrayerStatus.OTW }
    }

    private fun formatRemainingTime(targetEpoch: Long?): String {
        if (targetEpoch == null) return ""
        val diffMillis = targetEpoch - System.currentTimeMillis()
        if (diffMillis <= 0) return "Waktu salat telah masuk"

        val minutes = (diffMillis / (1000 * 60)) % 60
        val hours = (diffMillis / (1000 * 60 * 60))
        return if (hours > 0) {
            "$hours jam $minutes menit lagi"
        } else {
            "$minutes menit lagi"
        }
    }

    fun onYesClicked(prayerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            confirmPrayerUseCase(prayerId)
        }
    }

    fun onNoClicked(prayerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            processNoUseCase(prayerId)
            val settings = settingsRepository.settingsFlow.first()
            val prayer = _uiState.value.todayPrayers.find { it.id == prayerId }
            if (prayer != null) {
                alarmScheduler.scheduleNoSnooze(
                    prayerId = prayerId,
                    prayerName = prayer.effectiveDisplayName,
                    delayMinutes = settings.noSnoozeIntervalMinutes,
                    notificationId = 3000 + prayer.prayerName.order
                )
            }
        }
    }

    fun onOtwClicked(prayerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            processOtwUseCase(prayerId)
            val settings = settingsRepository.settingsFlow.first()
            val prayer = _uiState.value.todayPrayers.find { it.id == prayerId }
            if (prayer != null) {
                alarmScheduler.scheduleOtwFollowUp(
                    prayerId = prayerId,
                    prayerName = prayer.effectiveDisplayName,
                    delayMinutes = settings.otwIntervalMinutes,
                    notificationId = 4000 + prayer.prayerName.order
                )
            }
        }
    }

    class Factory(
        private val getTodayPrayersUseCase: GetTodayPrayersUseCase,
        private val confirmPrayerUseCase: ConfirmPrayerUseCase,
        private val processNoUseCase: ProcessNoUseCase,
        private val processOtwUseCase: ProcessOtwUseCase,
        private val markPrayerMissedUseCase: MarkPrayerMissedUseCase,
        private val reconcileMissedPrayersUseCase: ReconcileMissedPrayersUseCase,
        private val settingsRepository: AppSettingsRepository,
        private val alarmScheduler: PrayerAlarmScheduler
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(
                getTodayPrayersUseCase,
                confirmPrayerUseCase,
                processNoUseCase,
                processOtwUseCase,
                markPrayerMissedUseCase,
                reconcileMissedPrayersUseCase,
                settingsRepository,
                alarmScheduler
            ) as T
        }
    }
}
