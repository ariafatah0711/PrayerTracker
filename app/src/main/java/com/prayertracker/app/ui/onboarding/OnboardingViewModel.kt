package com.prayertracker.app.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prayertracker.app.core.datastore.AppSettingsRepository
import com.prayertracker.app.core.location.LocationHelper
import com.prayertracker.app.core.model.PrayerStatus
import com.prayertracker.app.domain.model.PredefinedCity
import com.prayertracker.app.domain.model.PrayerItem
import com.prayertracker.app.domain.model.allPredefinedCities
import com.prayertracker.app.domain.model.defaultIndonesianCities
import com.prayertracker.app.domain.model.supportedCountries
import com.prayertracker.app.domain.usecase.ConfirmPrayerUseCase
import com.prayertracker.app.domain.usecase.GetTodayPrayersUseCase
import com.prayertracker.app.domain.usecase.MarkPrayerMissedUseCase
import com.prayertracker.app.scheduler.PrayerAlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class OnboardingUiState(
    val currentStep: Int = 0, // 0: Lokasi, 1: Cek Salat Hari Ini, 2: Ringkasan & Siap
    val selectedCity: PredefinedCity = PredefinedCity("Depok", -6.4025, 106.7942, "ID", "Jawa Barat"),
    val selectedCountryCode: String = "ID",
    val citySearchQuery: String = "",
    val isGpsDetecting: Boolean = false,
    val gpsMessage: String? = null,
    val pastPrayers: List<PrayerItem> = emptyList(),
    val nextUpcomingPrayer: PrayerItem? = null,
    val checkedPrayerIds: Set<String> = emptySet(), // Default KOSONG (tidak ada yang tercentang otomatis)
    val isSaving: Boolean = false
)

class OnboardingViewModel(
    private val settingsRepository: AppSettingsRepository,
    private val getTodayPrayersUseCase: GetTodayPrayersUseCase,
    private val confirmPrayerUseCase: ConfirmPrayerUseCase,
    private val markPrayerMissedUseCase: MarkPrayerMissedUseCase,
    private val alarmScheduler: PrayerAlarmScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    val countries = supportedCountries
    val allCities = allPredefinedCities
    val popularIndonesianCities = defaultIndonesianCities

    init {
        initCurrentLocationAndPrayers()
    }

    private fun initCurrentLocationAndPrayers() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentSettings = settingsRepository.settingsFlow.first()
            val initialCity = allCities.find { it.name.equals(currentSettings.cityName, ignoreCase = true) }
                ?: PredefinedCity("Depok", -6.4025, 106.7942, "ID", "Jawa Barat")

            _uiState.update { it.copy(selectedCity = initialCity) }
            recalculatePrayers(initialCity.lat, initialCity.lng)
        }
    }

    fun onCitySelected(city: PredefinedCity) {
        _uiState.update { it.copy(selectedCity = city) }
        viewModelScope.launch(Dispatchers.IO) {
            recalculatePrayers(city.lat, city.lng)
        }
    }

    fun onCountrySelected(countryCode: String) {
        _uiState.update { it.copy(selectedCountryCode = countryCode, citySearchQuery = "") }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(citySearchQuery = query) }
    }

    fun detectGps(context: Context, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isGpsDetecting = true, gpsMessage = null) }
            val res = LocationHelper.getCurrentDeviceLocation(context)
            if (res.isSuccess) {
                val (cityName, lat, lng) = res.getOrThrow()
                val matchedCity = allCities.find { it.name.equals(cityName, ignoreCase = true) }
                    ?: PredefinedCity(cityName, lat, lng, "ID", "Otomatis GPS")

                _uiState.update {
                    it.copy(
                        selectedCity = matchedCity,
                        isGpsDetecting = false,
                        gpsMessage = "Lokasi terdeteksi: $cityName"
                    )
                }
                recalculatePrayers(lat, lng)
                withContext(Dispatchers.Main) {
                    onComplete(true, "Lokasi berhasil terdeteksi: $cityName")
                }
            } else {
                val error = res.exceptionOrNull()?.message ?: "Gagal mendeteksi lokasi GPS"
                _uiState.update { it.copy(isGpsDetecting = false, gpsMessage = error) }
                withContext(Dispatchers.Main) {
                    onComplete(false, error)
                }
            }
        }
    }

    private suspend fun recalculatePrayers(lat: Double, lng: Double) {
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        val prayers = getTodayPrayersUseCase.ensureAndGet(
            date = today,
            latitude = lat,
            longitude = lng,
            zoneId = ZoneId.systemDefault()
        )

        val past = prayers.filter { it.scheduledEpoch <= now }
        val next = prayers.firstOrNull { it.scheduledEpoch > now }

        _uiState.update {
            it.copy(
                pastPrayers = past,
                nextUpcomingPrayer = next
            )
        }
    }

    fun togglePrayerChecked(prayerId: String) {
        _uiState.update { current ->
            val updated = if (current.checkedPrayerIds.contains(prayerId)) {
                current.checkedPrayerIds - prayerId
            } else {
                current.checkedPrayerIds + prayerId
            }
            current.copy(checkedPrayerIds = updated)
        }
    }

    fun selectAllPrayers() {
        _uiState.update { current ->
            val allIds = current.pastPrayers.map { it.id }.toSet()
            current.copy(checkedPrayerIds = allIds)
        }
    }

    fun clearAllPrayers() {
        _uiState.update { it.copy(checkedPrayerIds = emptySet()) }
    }

    fun goToNextStep() {
        _uiState.update { it.copy(currentStep = (it.currentStep + 1).coerceAtMost(2)) }
    }

    fun goToPreviousStep() {
        _uiState.update { it.copy(currentStep = (it.currentStep - 1).coerceAtLeast(0)) }
    }

    fun finishOnboarding(onFinished: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isSaving = true) }
            val state = _uiState.value
            val city = state.selectedCity

            // 1. Simpan lokasi
            settingsRepository.updateLocation(city.name, city.lat, city.lng)

            // 2. Simpan status salat hari ini
            state.pastPrayers.forEach { prayer ->
                if (state.checkedPrayerIds.contains(prayer.id)) {
                    confirmPrayerUseCase(prayer.id)
                } else {
                    markPrayerMissedUseCase(prayer.id)
                }
            }

            // 3. Jadwalkan alarm untuk salat yang belum masuk
            val today = LocalDate.now()
            val allPrayers = getTodayPrayersUseCase.ensureAndGet(
                date = today,
                latitude = city.lat,
                longitude = city.lng,
                zoneId = ZoneId.systemDefault()
            )
            allPrayers.forEachIndexed { index, prayer ->
                if (prayer.status == PrayerStatus.PENDING && prayer.scheduledEpoch > System.currentTimeMillis()) {
                    alarmScheduler.schedulePrayerEntry(
                        prayerId = prayer.id,
                        prayerName = prayer.effectiveDisplayName,
                        triggerEpoch = prayer.scheduledEpoch,
                        notificationId = 1000 + index
                    )
                }
            }

            // 4. Tandai onboarding selesai
            settingsRepository.setOnboardingCompleted(true)

            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isSaving = false) }
                onFinished()
            }
        }
    }

    class Factory(
        private val settingsRepository: AppSettingsRepository,
        private val getTodayPrayersUseCase: GetTodayPrayersUseCase,
        private val confirmPrayerUseCase: ConfirmPrayerUseCase,
        private val markPrayerMissedUseCase: MarkPrayerMissedUseCase,
        private val alarmScheduler: PrayerAlarmScheduler
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return OnboardingViewModel(
                settingsRepository,
                getTodayPrayersUseCase,
                confirmPrayerUseCase,
                markPrayerMissedUseCase,
                alarmScheduler
            ) as T
        }
    }
}
