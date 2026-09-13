package com.prayertracker.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.prayertracker.app.core.datastore.AppSettings
import com.prayertracker.app.core.datastore.AppSettingsRepository
import com.prayertracker.app.core.sync.GoogleAuthManager
import com.prayertracker.app.core.sync.LocalBackupManager
import com.prayertracker.app.core.sync.SyncCoordinator
import com.prayertracker.app.domain.calculation.CalculationMethod
import com.prayertracker.app.domain.calculation.Madhab
import com.prayertracker.app.domain.usecase.ResetAllDataUseCase
import com.prayertracker.app.notification.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.prayertracker.app.domain.model.PredefinedCity
import com.prayertracker.app.domain.model.defaultIndonesianCities

enum class CloudSyncAction {
    UPLOAD,
    PULL
}

class SettingsViewModel(
    private val settingsRepository: AppSettingsRepository,
    private val googleAuthManager: GoogleAuthManager,
    private val syncCoordinator: SyncCoordinator,
    private val notificationHelper: NotificationHelper,
    private val resetAllDataUseCase: ResetAllDataUseCase,
    private val localBackupManager: LocalBackupManager
) : ViewModel() {

    val supportedCountries = com.prayertracker.app.domain.model.supportedCountries
    val allCities = com.prayertracker.app.domain.model.allPredefinedCities
    val predefinedCities = defaultIndonesianCities

    fun updateCustomLocation(cityName: String, lat: Double, lng: Double) {
        viewModelScope.launch {
            settingsRepository.updateLocation(cityName, lat, lng)
            _syncMessage.value = "Lokasi diatur ke: $cityName"
        }
    }

    fun detectGpsLocation(context: Context, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = com.prayertracker.app.core.location.LocationHelper.getCurrentDeviceLocation(context)
            if (res.isSuccess) {
                val (city, lat, lng) = res.getOrThrow()
                settingsRepository.updateLocation(city, lat, lng)
                withContext(Dispatchers.Main) {
                    _syncMessage.value = "Lokasi terdeteksi otomatis: $city"
                    onResult(true, "Lokasi berhasil terdeteksi: $city")
                }
            } else {
                withContext(Dispatchers.Main) {
                    val msg = res.exceptionOrNull()?.message ?: "Gagal mendeteksi lokasi GPS"
                    _syncMessage.value = msg
                    onResult(false, msg)
                }
            }
        }
    }

    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings()
    )

    val isLoaded: StateFlow<Boolean> = settingsRepository.settingsFlow
        .map { true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _activeCloudSyncAction = MutableStateFlow<CloudSyncAction?>(null)
    val activeCloudSyncAction: StateFlow<CloudSyncAction?> = _activeCloudSyncAction.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private val _showSyncChoiceDialog = MutableStateFlow(false)
    val showSyncChoiceDialog: StateFlow<Boolean> = _showSyncChoiceDialog.asStateFlow()

    private val _showTrashCloudConfirmDialog = MutableStateFlow(false)
    val showTrashCloudConfirmDialog: StateFlow<Boolean> = _showTrashCloudConfirmDialog.asStateFlow()

    fun triggerTestNotification() {
        viewModelScope.launch {
            val s = settingsRepository.settingsFlow.first()
            notificationHelper.showPrayerIncomingNotification(
                prayerId = "test_preview_id",
                prayerName = "Dzuhur (Uji Coba)",
                timeFormatted = "Sekarang",
                notificationId = 9999,
                otwMinutes = s.otwIntervalMinutes,
                noSnoozeMinutes = s.noSnoozeIntervalMinutes
            )
            _syncMessage.value = "Notifikasi Heads-Up banner berhasil dikirim!"
        }
    }

    fun resetAllData(onSuccess: () -> Unit) {
        viewModelScope.launch {
            val res = resetAllDataUseCase()
            if (res.isSuccess) {
                settingsRepository.resetAllSettings()
                onSuccess()
            } else {
                _syncMessage.value = "Gagal mereset data: ${res.exceptionOrNull()?.localizedMessage}"
            }
        }
    }

    fun getGoogleSignInIntent(): Intent {
        return googleAuthManager.getSignInIntent()
    }

    fun handleSignInResult(data: Intent?) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            if (account != null && account.email != null) {
                viewModelScope.launch {
                    settingsRepository.updateGoogleAccount(account.email)
                    _showSyncChoiceDialog.value = true
                    _syncMessage.value = "Berhasil terhubung ke ${account.email}. Silakan pilih arah sinkronisasi."
                }
            } else {
                _syncMessage.value = "Login dibatalkan atau akun tidak ditemukan"
            }
        } catch (e: ApiException) {
            val details = when (e.statusCode) {
                10 -> "Developer Error (SHA-1 belum didaftarkan di Google Cloud Console). Gunakan Ekspor Spreadsheet (.CSV) di bawah untuk cadangkan langsung."
                12500 -> "Sign-in Gagal (12500: Konfigurasi OAuth Google Play Services belum lengkap)."
                7 -> "Jaringan internet tidak tersedia."
                else -> "Kode error Google: ${e.statusCode} (${e.localizedMessage})"
            }
            _syncMessage.value = details
        } catch (e: Exception) {
            _syncMessage.value = "Gagal login Google: ${e.localizedMessage}"
        }
    }

    fun exportToCsv(context: Context, onReady: (Intent) -> Unit) {
        viewModelScope.launch {
            val result = localBackupManager.exportToCsv(context)
            if (result.isSuccess) {
                onReady(result.getOrThrow())
                _syncMessage.value = "Berhasil membuat file Excel / CSV riwayat salat!"
            } else {
                _syncMessage.value = "Gagal mengekspor CSV: ${result.exceptionOrNull()?.localizedMessage}"
            }
        }
    }

    fun exportBackupJson(context: Context, onReady: (Intent) -> Unit) {
        viewModelScope.launch {
            val result = localBackupManager.exportBackupJson(context)
            if (result.isSuccess) {
                onReady(result.getOrThrow())
                _syncMessage.value = "Berhasil membuat file cadangan database (JSON)!"
            } else {
                _syncMessage.value = "Gagal membuat cadangan JSON: ${result.exceptionOrNull()?.localizedMessage}"
            }
        }
    }

    fun restoreFromJsonUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Sedang memulihkan database dari file cadangan..."
            val result = localBackupManager.restoreFromJsonUri(context, uri)
            _isSyncing.value = false
            if (result.isSuccess) {
                syncCoordinator.notifyDataRefreshed()
                _syncMessage.value = "Berhasil memulihkan ${result.getOrThrow()} data salat!"
            } else {
                _syncMessage.value = "Gagal memulihkan cadangan: ${result.exceptionOrNull()?.localizedMessage}"
            }
        }
    }

    fun disconnectGoogle() {
        viewModelScope.launch {
            googleAuthManager.signOut()
            settingsRepository.updateGoogleAccount(null)
            _syncMessage.value = "Akun Google telah diputuskan"
        }
    }

    fun performSync() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Sedang menyinkronkan data..."
            val result = syncCoordinator.performFullSync()
            _isSyncing.value = false
            if (result.isSuccess) {
                _syncMessage.value = "Sinkronisasi berhasil!"
            } else {
                _syncMessage.value = "Gagal sinkron: ${result.exceptionOrNull()?.localizedMessage}"
            }
        }
    }

    fun performRestore() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Sedang memulihkan data dari Google Drive..."
            val result = syncCoordinator.restoreData()
            _isSyncing.value = false
            if (result.isSuccess) {
                _syncMessage.value = "Berhasil memulihkan ${result.getOrNull()} catatan salat!"
            } else {
                _syncMessage.value = "Gagal memulihkan data: ${result.exceptionOrNull()?.localizedMessage}"
            }
        }
    }

    fun onSelectSyncCloudToLocal() {
        _showSyncChoiceDialog.value = false
        viewModelScope.launch {
            _isSyncing.value = true
            _activeCloudSyncAction.value = CloudSyncAction.PULL
            _syncMessage.value = "Sedang menarik data dari Cloud ke HP..."
            try {
                val res = syncCoordinator.syncCloudToLocal()
                _syncMessage.value = if (res.isSuccess) res.getOrNull() else "Gagal: ${res.exceptionOrNull()?.localizedMessage}"
            } catch (e: Exception) {
                _syncMessage.value = "Gagal menarik data: ${e.localizedMessage}"
            } finally {
                _isSyncing.value = false
                _activeCloudSyncAction.value = null
            }
        }
    }

    fun onSelectSyncLocalToCloud() {
        _showSyncChoiceDialog.value = false
        viewModelScope.launch {
            _isSyncing.value = true
            _activeCloudSyncAction.value = CloudSyncAction.UPLOAD
            _syncMessage.value = "Sedang mengunggah data HP ke Cloud..."
            try {
                val res = syncCoordinator.syncLocalToCloud()
                _syncMessage.value = if (res.isSuccess) "Data HP berhasil diunggah ke Google Drive & Spreadsheet!" else "Gagal: ${res.exceptionOrNull()?.localizedMessage}"
            } catch (e: Exception) {
                _syncMessage.value = "Gagal mengunggah data: ${e.localizedMessage}"
            } finally {
                _isSyncing.value = false
                _activeCloudSyncAction.value = null
            }
        }
    }

    fun onSelectSyncSmartMerge() {
        _showSyncChoiceDialog.value = false
        performSync()
    }

    fun openSyncChoiceDialog() {
        _showSyncChoiceDialog.value = true
    }

    fun dismissSyncChoiceDialog() {
        _showSyncChoiceDialog.value = false
    }

    fun openTrashCloudConfirmDialog() {
        _showTrashCloudConfirmDialog.value = true
    }

    fun dismissTrashCloudConfirmDialog() {
        _showTrashCloudConfirmDialog.value = false
    }

    fun confirmTrashCloudData() {
        _showTrashCloudConfirmDialog.value = false
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Sedang memindahkan cadangan Cloud ke Sampah..."
            val res = syncCoordinator.trashCloudData()
            _isSyncing.value = false
            if (res.isSuccess) {
                _syncMessage.value = "Cadangan Google Drive & Spreadsheet berhasil dipindahkan ke Sampah Google Drive!"
            } else {
                _syncMessage.value = "Gagal memindahkan ke sampah: ${res.exceptionOrNull()?.localizedMessage}"
            }
        }
    }

    fun clearSyncMessage() {
        _syncMessage.value = null
    }

    fun onCitySelected(city: PredefinedCity) {
        viewModelScope.launch {
            settingsRepository.updateLocation(city.name, city.lat, city.lng)
        }
    }

    fun onOtwIntervalChanged(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.updateOtwInterval(minutes)
        }
    }

    fun onNoIntervalChanged(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.updateNoSnoozeInterval(minutes)
        }
    }

    fun onCalculationMethodChanged(method: CalculationMethod) {
        viewModelScope.launch {
            settingsRepository.updateCalculationMethod(method)
        }
    }

    fun onMadhabChanged(madhab: Madhab) {
        viewModelScope.launch {
            settingsRepository.updateMadhab(madhab)
        }
    }

    fun onNotificationTogglesChanged(enabled: Boolean, sound: Boolean, vibration: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateNotificationToggles(enabled, sound, vibration)
        }
    }

    fun updateOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateOverlayEnabled(enabled)
        }
    }

    fun triggerDelayedOverlayTest(context: Context) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(5000)
            val s = settingsRepository.settingsFlow.first()
            val testNotificationId = 9997
            // Kirim notifikasi status bar bersamaan agar sinkron dengan overlay
            notificationHelper.showPrayerIncomingNotification(
                prayerId = "test_delayed_preview_id",
                prayerName = "Maghrib (Uji Melayang)",
                timeFormatted = "18:05 WIB",
                notificationId = testNotificationId,
                otwMinutes = s.otwIntervalMinutes,
                noSnoozeMinutes = s.noSnoozeIntervalMinutes
            )
            val intent = com.prayertracker.app.ui.overlay.PrayerAlarmDialogActivity.createIntent(
                context = context,
                prayerId = "test_delayed_preview_id",
                prayerName = "Maghrib (Uji Melayang)",
                timeFormatted = "18:05 WIB",
                notificationId = testNotificationId,
                alertType = "ENTRY"
            )
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    class Factory(
        private val settingsRepository: AppSettingsRepository,
        private val googleAuthManager: GoogleAuthManager,
        private val syncCoordinator: SyncCoordinator,
        private val notificationHelper: NotificationHelper,
        private val resetAllDataUseCase: ResetAllDataUseCase,
        private val localBackupManager: LocalBackupManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                settingsRepository,
                googleAuthManager,
                syncCoordinator,
                notificationHelper,
                resetAllDataUseCase,
                localBackupManager
            ) as T
        }
    }
}
