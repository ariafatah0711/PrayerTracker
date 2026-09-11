package com.prayertracker.app.domain.calculation

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.*

interface PrayerTimeProvider {
    fun calculatePrayerTimes(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zoneId: ZoneId,
        method: CalculationMethod = CalculationMethod.KEMENAG,
        madhab: Madhab = Madhab.SHAFI_STANDARD
    ): PrayerTimesResult
}

class AstronomicalPrayerCalculator : PrayerTimeProvider {

    override fun calculatePrayerTimes(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zoneId: ZoneId,
        method: CalculationMethod,
        madhab: Madhab
    ): PrayerTimesResult {
        val year = date.year
        val month = date.monthValue
        val day = date.dayOfMonth

        val julianDay = computeJulianDay(year, month, day)
        val d = julianDay - 2451545.0

        // Sun's Mean Anomaly and Mean Longitude
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * sin(toRadians(g)) + 0.020 * sin(toRadians(2 * g)))

        // Obliquity of the ecliptic
        val e = 23.439 - 0.00000036 * d
        val sinD = sin(toRadians(e)) * sin(toRadians(l))
        val declination = toDegrees(asin(sinD))

        // Equation of time in minutes
        val ra = toDegrees(atan2(cos(toRadians(e)) * sin(toRadians(l)), cos(toRadians(l)))) / 15.0
        val eqt = (q / 15.0 - fixHour(ra)) * 60.0

        // Timezone offset in hours
        val zonedDateTime = date.atStartOfDay(zoneId)
        val timezoneOffsetHours = zonedDateTime.offset.totalSeconds / 3600.0

        // Midday (Dhuhr) in local hours
        val dhuhrLocalHours = 12.0 + timezoneOffsetHours - (longitude / 15.0) - (eqt / 60.0)

        // Sunrise and Sunset (approx angle 0.833)
        val sunAlt = 0.833
        val sunriseHourAngle = computeHourAngle(latitude, declination, -sunAlt)
        val sunriseLocalHours = dhuhrLocalHours - (sunriseHourAngle / 15.0)
        val sunsetLocalHours = dhuhrLocalHours + (sunriseHourAngle / 15.0)

        // Fajr (Subuh)
        val fajrHourAngle = computeHourAngle(latitude, declination, -method.fajrAngle)
        val fajrLocalHours = dhuhrLocalHours - (fajrHourAngle / 15.0)

        // Asr
        val asrAlt = toDegrees(atan(1.0 / (madhab.shadowFactor + tan(toRadians(abs(latitude - declination))))))
        val asrHourAngle = computeHourAngle(latitude, declination, asrAlt)
        val asrLocalHours = dhuhrLocalHours + (asrHourAngle / 15.0)

        // Maghrib (Sunset + 2 min buffer / Ihtiyat)
        val maghribLocalHours = sunsetLocalHours + (2.0 / 60.0)

        // Isha
        val ishaLocalHours = if (method.isIshaInterval) {
            maghribLocalHours + (method.ishaIntervalMinutes / 60.0)
        } else {
            val ishaHourAngle = computeHourAngle(latitude, declination, -method.ishaAngle)
            dhuhrLocalHours + (ishaHourAngle / 15.0)
        }

        // Convert decimal hours to epoch millis
        val fajrEpoch = decimalHoursToEpoch(date, fajrLocalHours, zoneId)
        val sunriseEpoch = decimalHoursToEpoch(date, sunriseLocalHours, zoneId)
        val dhuhrEpoch = decimalHoursToEpoch(date, dhuhrLocalHours + (2.0 / 60.0), zoneId) // 2 min ihtiyat
        val asrEpoch = decimalHoursToEpoch(date, asrLocalHours + (2.0 / 60.0), zoneId)
        val maghribEpoch = decimalHoursToEpoch(date, maghribLocalHours, zoneId)
        val ishaEpoch = decimalHoursToEpoch(date, ishaLocalHours + (2.0 / 60.0), zoneId)

        // Next Day Fajr for calculating Isha end_time
        val nextFajrEpoch = calculateNextDayFajr(date.plusDays(1), latitude, longitude, zoneId, method)

        return PrayerTimesResult(
            fajrEpoch = fajrEpoch,
            sunriseEpoch = sunriseEpoch,
            dhuhrEpoch = dhuhrEpoch,
            asrEpoch = asrEpoch,
            maghribEpoch = maghribEpoch,
            ishaEpoch = ishaEpoch,
            nextFajrEpoch = nextFajrEpoch
        )
    }

    private fun calculateNextDayFajr(
        nextDate: LocalDate,
        latitude: Double,
        longitude: Double,
        zoneId: ZoneId,
        method: CalculationMethod
    ): Long {
        val year = nextDate.year
        val month = nextDate.monthValue
        val day = nextDate.dayOfMonth
        val julianDay = computeJulianDay(year, month, day)
        val d = julianDay - 2451545.0

        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * sin(toRadians(g)) + 0.020 * sin(toRadians(2 * g)))
        val e = 23.439 - 0.00000036 * d
        val sinD = sin(toRadians(e)) * sin(toRadians(l))
        val declination = toDegrees(asin(sinD))
        val ra = toDegrees(atan2(cos(toRadians(e)) * sin(toRadians(l)), cos(toRadians(l)))) / 15.0
        val eqt = (q / 15.0 - fixHour(ra)) * 60.0

        val timezoneOffsetHours = nextDate.atStartOfDay(zoneId).offset.totalSeconds / 3600.0
        val dhuhrLocalHours = 12.0 + timezoneOffsetHours - (longitude / 15.0) - (eqt / 60.0)
        val fajrHourAngle = computeHourAngle(latitude, declination, -method.fajrAngle)
        val fajrLocalHours = dhuhrLocalHours - (fajrHourAngle / 15.0)

        return decimalHoursToEpoch(nextDate, fajrLocalHours, zoneId)
    }

    private fun computeJulianDay(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun computeHourAngle(latitude: Double, declination: Double, altitude: Double): Double {
        val latRad = toRadians(latitude)
        val decRad = toRadians(declination)
        val altRad = toRadians(altitude)
        val cosHA = (sin(altRad) - sin(latRad) * sin(decRad)) / (cos(latRad) * cos(decRad))
        val clamped = cosHA.coerceIn(-1.0, 1.0)
        return toDegrees(acos(clamped))
    }

    private fun decimalHoursToEpoch(date: LocalDate, decimalHours: Double, zoneId: ZoneId): Long {
        var normalized = decimalHours
        while (normalized < 0) normalized += 24.0
        while (normalized >= 24) normalized -= 24.0

        val hours = normalized.toInt()
        val totalMinutes = (normalized - hours) * 60.0
        val minutes = totalMinutes.toInt()
        val seconds = ((totalMinutes - minutes) * 60.0).toInt().coerceIn(0, 59)

        val localTime = LocalTime.of(hours.coerceIn(0, 23), minutes.coerceIn(0, 59), seconds)
        val localDateTime = LocalDateTime.of(date, localTime)
        return localDateTime.atZone(zoneId).toInstant().toEpochMilli()
    }

    private fun fixAngle(a: Double): Double {
        var res = a - 360.0 * floor(a / 360.0)
        if (res < 0) res += 360.0
        return res
    }

    private fun fixHour(h: Double): Double {
        var res = h - 24.0 * floor(h / 24.0)
        if (res < 0) res += 24.0
        return res
    }

    private fun toRadians(deg: Double): Double = Math.toRadians(deg)
    private fun toDegrees(rad: Double): Double = Math.toDegrees(rad)
}
