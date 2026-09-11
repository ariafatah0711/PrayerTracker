package com.prayertracker.app

import com.prayertracker.app.domain.calculation.AstronomicalPrayerCalculator
import com.prayertracker.app.domain.calculation.CalculationMethod
import com.prayertracker.app.domain.calculation.Madhab
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AstronomicalPrayerCalculatorTest {

    private val calculator = AstronomicalPrayerCalculator()

    @Test
    fun testJakartaPrayerTimesSequenceAndBounds() {
        val date = LocalDate.of(2026, 9, 11)
        val lat = -6.2088 // Jakarta
        val lng = 106.8456
        val zoneId = ZoneId.of("Asia/Jakarta")

        val result = calculator.calculatePrayerTimes(
            date = date,
            latitude = lat,
            longitude = lng,
            zoneId = zoneId,
            method = CalculationMethod.KEMENAG,
            madhab = Madhab.SHAFI_STANDARD
        )

        // Subuh must be before Sunrise
        assertTrue("Fajr must be before Sunrise", result.fajrEpoch < result.sunriseEpoch)
        // Sunrise must be before Dhuhr
        assertTrue("Sunrise must be before Dhuhr", result.sunriseEpoch < result.dhuhrEpoch)
        // Dhuhr must be before Asr
        assertTrue("Dhuhr must be before Asr", result.dhuhrEpoch < result.asrEpoch)
        // Asr must be before Maghrib
        assertTrue("Asr must be before Maghrib", result.asrEpoch < result.maghribEpoch)
        // Maghrib must be before Isha
        assertTrue("Maghrib must be before Isha", result.maghribEpoch < result.ishaEpoch)
        // Isha must be before Next Fajr
        assertTrue("Isha must be before Next Fajr", result.ishaEpoch < result.nextFajrEpoch)
    }

    @Test
    fun testMadhabHanafiDelaysAsr() {
        val date = LocalDate.of(2026, 9, 11)
        val lat = -6.2088
        val lng = 106.8456
        val zoneId = ZoneId.of("Asia/Jakarta")

        val shafiResult = calculator.calculatePrayerTimes(
            date, lat, lng, zoneId, CalculationMethod.KEMENAG, Madhab.SHAFI_STANDARD
        )
        val hanafiResult = calculator.calculatePrayerTimes(
            date, lat, lng, zoneId, CalculationMethod.KEMENAG, Madhab.HANAFI
        )

        // Hanafi shadow factor is 2, so Asr starts later than Shafi'i shadow factor 1
        assertTrue("Hanafi Asr should start later than Shafi'i Asr", hanafiResult.asrEpoch > shafiResult.asrEpoch)
    }
}
