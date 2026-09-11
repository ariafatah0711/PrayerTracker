package com.prayertracker.app.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.prayertracker.app.domain.calculation.CalculationMethod
import com.prayertracker.app.domain.calculation.Madhab
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "prayer_tracker_settings")

data class AppSettings(
    val cityName: String = "Jakarta",
    val latitude: Double = -6.2088,
    val longitude: Double = 106.8456,
    val calculationMethod: CalculationMethod = CalculationMethod.KEMENAG,
    val madhab: Madhab = Madhab.SHAFI_STANDARD,
    val otwIntervalMinutes: Int = 3,
    val noSnoozeIntervalMinutes: Int = 10,
    val isNotificationEnabled: Boolean = true,
    val isSoundEnabled: Boolean = true,
    val isVibrationEnabled: Boolean = true,
    val googleAccountEmail: String? = null,
    val lastSyncEpoch: Long? = null,
    val spreadsheetUrl: String? = null,
    val installedAtEpoch: Long = 0L,
    val isOverlayEnabled: Boolean = true,
    val isOnboardingCompleted: Boolean = false
) {
    val isGoogleConnected: Boolean
        get() = !googleAccountEmail.isNullOrBlank()
}

class AppSettingsRepository(private val context: Context) {

    private object Keys {
        val CITY_NAME = stringPreferencesKey("city_name")
        val LATITUDE = doublePreferencesKey("latitude")
        val LONGITUDE = doublePreferencesKey("longitude")
        val CALC_METHOD = stringPreferencesKey("calc_method")
        val MADHAB = stringPreferencesKey("madhab")
        val OTW_INTERVAL = intPreferencesKey("otw_interval")
        val NO_INTERVAL = intPreferencesKey("no_interval")
        val NOTIF_ENABLED = booleanPreferencesKey("notif_enabled")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val VIBE_ENABLED = booleanPreferencesKey("vibe_enabled")
        val GOOGLE_EMAIL = stringPreferencesKey("google_email")
        val LAST_SYNC_EPOCH = longPreferencesKey("last_sync_epoch")
        val SPREADSHEET_URL = stringPreferencesKey("spreadsheet_url")
        val INSTALLED_AT = longPreferencesKey("installed_at_epoch")
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val installedAt = prefs[Keys.INSTALLED_AT] ?: System.currentTimeMillis()
        if (!prefs.contains(Keys.INSTALLED_AT)) {
            context.dataStore.edit { it[Keys.INSTALLED_AT] = installedAt }
        }

        AppSettings(
            cityName = prefs[Keys.CITY_NAME] ?: "Jakarta",
            latitude = prefs[Keys.LATITUDE] ?: -6.2088,
            longitude = prefs[Keys.LONGITUDE] ?: 106.8456,
            calculationMethod = prefs[Keys.CALC_METHOD]?.let { CalculationMethod.valueOf(it) } ?: CalculationMethod.KEMENAG,
            madhab = prefs[Keys.MADHAB]?.let { Madhab.valueOf(it) } ?: Madhab.SHAFI_STANDARD,
            otwIntervalMinutes = prefs[Keys.OTW_INTERVAL] ?: 3,
            noSnoozeIntervalMinutes = prefs[Keys.NO_INTERVAL] ?: 10,
            isNotificationEnabled = prefs[Keys.NOTIF_ENABLED] ?: true,
            isSoundEnabled = prefs[Keys.SOUND_ENABLED] ?: true,
            isVibrationEnabled = prefs[Keys.VIBE_ENABLED] ?: true,
            googleAccountEmail = prefs[Keys.GOOGLE_EMAIL],
            lastSyncEpoch = prefs[Keys.LAST_SYNC_EPOCH],
            spreadsheetUrl = prefs[Keys.SPREADSHEET_URL],
            installedAtEpoch = installedAt,
            isOverlayEnabled = prefs[Keys.OVERLAY_ENABLED] ?: true,
            isOnboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false
        )
    }

    suspend fun setOnboardingCompleted(completed: Boolean = true) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETED] = completed
        }
    }

    suspend fun resetAllSettings() {
        context.dataStore.edit { prefs ->
            prefs.clear()
            prefs[Keys.INSTALLED_AT] = System.currentTimeMillis()
            prefs[Keys.ONBOARDING_COMPLETED] = false
        }
    }

    suspend fun resetInstalledAt(epoch: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs ->
            prefs[Keys.INSTALLED_AT] = epoch
        }
    }

    suspend fun updateGoogleAccount(email: String?) {
        context.dataStore.edit { prefs ->
            if (email != null) {
                prefs[Keys.GOOGLE_EMAIL] = email
            } else {
                prefs.remove(Keys.GOOGLE_EMAIL)
                prefs.remove(Keys.LAST_SYNC_EPOCH)
                prefs.remove(Keys.SPREADSHEET_URL)
            }
        }
    }

    suspend fun updateSyncInfo(epoch: Long, spreadsheetUrl: String?) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_SYNC_EPOCH] = epoch
            if (spreadsheetUrl != null) {
                prefs[Keys.SPREADSHEET_URL] = spreadsheetUrl
            }
        }
    }

    suspend fun updateLocation(cityName: String, latitude: Double, longitude: Double) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CITY_NAME] = cityName
            prefs[Keys.LATITUDE] = latitude
            prefs[Keys.LONGITUDE] = longitude
        }
    }

    suspend fun updateOtwInterval(minutes: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.OTW_INTERVAL] = minutes
        }
    }

    suspend fun updateNoSnoozeInterval(minutes: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NO_INTERVAL] = minutes
        }
    }

    suspend fun updateCalculationMethod(method: CalculationMethod) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CALC_METHOD] = method.name
        }
    }

    suspend fun updateMadhab(madhab: Madhab) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MADHAB] = madhab.name
        }
    }

    suspend fun updateNotificationToggles(enabled: Boolean, sound: Boolean, vibration: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NOTIF_ENABLED] = enabled
            prefs[Keys.SOUND_ENABLED] = sound
            prefs[Keys.VIBE_ENABLED] = vibration
        }
    }

    suspend fun updateOverlayEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.OVERLAY_ENABLED] = enabled
        }
    }
}
