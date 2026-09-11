package com.prayertracker.app.core.model

enum class PrayerName(val displayName: String, val order: Int) {
    FAJR("Subuh", 1),
    DHUHR("Dzuhur", 2),
    ASR("Ashar", 3),
    MAGHRIB("Maghrib", 4),
    ISHA("Isya", 5);

    companion object {
        fun fromString(name: String): PrayerName? {
            return entries.find { it.name.equals(name, ignoreCase = true) || it.displayName.equals(name, ignoreCase = true) }
        }
    }
}
