package com.prayertracker.app.domain.calculation

enum class CalculationMethod(
    val displayName: String,
    val fajrAngle: Double,
    val ishaAngle: Double,
    val isIshaInterval: Boolean = false,
    val ishaIntervalMinutes: Int = 0
) {
    KEMENAG("Kemenag RI (Subuh 20°, Isya 18°)", 20.0, 18.0),
    MWL("Muslim World League (Subuh 18°, Isya 17°)", 18.0, 17.0),
    EGYPT("Egyptian General Authority (Subuh 19.5°, Isya 17.5°)", 19.5, 17.5),
    ISNA("Islamic Society of North America (Subuh 15°, Isya 15°)", 15.0, 15.0),
    UMM_AL_QURA("Umm Al-Qura, Makkah (Subuh 18.5°, Isya +90m)", 18.5, 0.0, true, 90)
}

enum class Madhab(val displayName: String, val shadowFactor: Double) {
    SHAFI_STANDARD("Syafi'i / Maliki / Hanbali (Faktor Bayangan 1)", 1.0),
    HANAFI("Hanafi (Faktor Bayangan 2)", 2.0)
}

data class PrayerTimesResult(
    val fajrEpoch: Long,
    val sunriseEpoch: Long,
    val dhuhrEpoch: Long,
    val asrEpoch: Long,
    val maghribEpoch: Long,
    val ishaEpoch: Long,
    val nextFajrEpoch: Long
)
