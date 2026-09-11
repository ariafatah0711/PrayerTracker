package com.prayertracker.app.domain.model

import com.prayertracker.app.core.model.PrayerName
import com.prayertracker.app.core.model.PrayerStatus

data class PrayerItem(
    val id: String,
    val prayerName: PrayerName,
    val prayerDate: String, // YYYY-MM-DD
    val scheduledEpoch: Long,
    val endEpoch: Long,
    val status: PrayerStatus,
    val completedAtEpoch: Long?,
    val formattedScheduledTime: String,
    val formattedEndTime: String,
    val formattedCompletedTime: String? = null
) {
    val isFriday: Boolean
        get() = try {
            java.time.LocalDate.parse(prayerDate).dayOfWeek == java.time.DayOfWeek.FRIDAY
        } catch (_: Exception) {
            false
        }

    val effectiveDisplayName: String
        get() = if (prayerName == PrayerName.DHUHR && isFriday) "Jum'at" else prayerName.displayName
}

data class QadhaItem(
    val prayerRecordId: String,
    val prayerName: PrayerName,
    val originalDate: String,
    val originalScheduledEpoch: Long,
    val formattedScheduledTime: String,
    val status: PrayerStatus,
    val qadhaAtEpoch: Long? = null,
    val formattedQadhaTime: String? = null
) {
    val isFriday: Boolean
        get() = try {
            java.time.LocalDate.parse(originalDate).dayOfWeek == java.time.DayOfWeek.FRIDAY
        } catch (_: Exception) {
            false
        }

    val effectiveDisplayName: String
        get() = if (prayerName == PrayerName.DHUHR && isFriday) "Jum'at" else prayerName.displayName
}

data class StatisticsData(
    val completedCount: Int,
    val missedCount: Int,
    val qadhaCompletedCount: Int,
    val completionPercentage: Int,
    val streakDays: Int
)

data class CountryItem(
    val code: String,
    val name: String,
    val flag: String
)

data class PredefinedCity(
    val name: String,
    val lat: Double,
    val lng: Double,
    val countryCode: String = "ID",
    val province: String = ""
)

val supportedCountries = listOf(
    CountryItem("ID", "Indonesia", "🇮🇩"),
    CountryItem("SA", "Arab Saudi", "🇸🇦"),
    CountryItem("MY", "Malaysia", "🇲🇾"),
    CountryItem("SG", "Singapura", "🇸🇬"),
    CountryItem("TR", "Turki", "🇹🇷"),
    CountryItem("EG", "Mesir", "🇪🇬"),
    CountryItem("AE", "Uni Emirat Arab", "🇦🇪"),
    CountryItem("GB", "Inggris (UK)", "🇬🇧"),
    CountryItem("US", "Amerika Serikat", "🇺🇸"),
    CountryItem("JP", "Jepang", "🇯🇵"),
    CountryItem("AU", "Australia", "🇦🇺"),
    CountryItem("DE", "Jerman", "🇩🇪")
)

val allPredefinedCities = listOf(
    // === INDONESIA (JABODETABEK & JAWA BARAT) ===
    PredefinedCity("Depok", -6.4025, 106.7942, "ID", "Jawa Barat"),
    PredefinedCity("Jakarta", -6.2088, 106.8456, "ID", "DKI Jakarta"),
    PredefinedCity("Bogor", -6.5971, 106.8060, "ID", "Jawa Barat"),
    PredefinedCity("Bekasi", -6.2383, 106.9756, "ID", "Jawa Barat"),
    PredefinedCity("Tangerang", -6.1783, 106.6319, "ID", "Banten"),
    PredefinedCity("Tangerang Selatan", -6.2886, 106.7179, "ID", "Banten"),
    PredefinedCity("Cikarang", -6.3056, 107.1539, "ID", "Jawa Barat"),
    PredefinedCity("Bandung", -6.9175, 107.6191, "ID", "Jawa Barat"),
    PredefinedCity("Cimahi", -6.8722, 107.5422, "ID", "Jawa Barat"),
    PredefinedCity("Cirebon", -6.7320, 108.5523, "ID", "Jawa Barat"),
    PredefinedCity("Sukabumi", -6.9277, 106.9300, "ID", "Jawa Barat"),
    PredefinedCity("Tasikmalaya", -7.3274, 108.2207, "ID", "Jawa Barat"),
    PredefinedCity("Garut", -7.2278, 107.9086, "ID", "Jawa Barat"),
    PredefinedCity("Karawang", -6.3073, 107.3075, "ID", "Jawa Barat"),
    PredefinedCity("Purwakarta", -6.5569, 107.4433, "ID", "Jawa Barat"),
    PredefinedCity("Subang", -6.5684, 107.7599, "ID", "Jawa Barat"),
    PredefinedCity("Cianjur", -6.8173, 107.1397, "ID", "Jawa Barat"),
    PredefinedCity("Sumedang", -6.8586, 107.9267, "ID", "Jawa Barat"),
    PredefinedCity("Indramayu", -6.3264, 108.3200, "ID", "Jawa Barat"),
    PredefinedCity("Majalengka", -6.8361, 108.2278, "ID", "Jawa Barat"),
    PredefinedCity("Kuningan", -6.9764, 108.4831, "ID", "Jawa Barat"),
    PredefinedCity("Ciamis", -7.3264, 108.3533, "ID", "Jawa Barat"),
    PredefinedCity("Serang", -6.1104, 106.1640, "ID", "Banten"),
    PredefinedCity("Cilegon", -6.0174, 106.0538, "ID", "Banten"),

    // === INDONESIA (JAWA TENGAH & DIY) ===
    PredefinedCity("Semarang", -6.9667, 110.4167, "ID", "Jawa Tengah"),
    PredefinedCity("Surakarta (Solo)", -7.5755, 110.8243, "ID", "Jawa Tengah"),
    PredefinedCity("Yogyakarta", -7.7956, 110.3695, "ID", "D.I. Yogyakarta"),
    PredefinedCity("Magelang", -7.4706, 110.2178, "ID", "Jawa Tengah"),
    PredefinedCity("Salatiga", -7.3305, 110.5084, "ID", "Jawa Tengah"),
    PredefinedCity("Pekalongan", -6.8886, 109.6753, "ID", "Jawa Tengah"),
    PredefinedCity("Tegal", -6.8694, 109.1402, "ID", "Jawa Tengah"),
    PredefinedCity("Purwokerto", -7.4243, 109.2302, "ID", "Jawa Tengah"),
    PredefinedCity("Cilacap", -7.7028, 109.0189, "ID", "Jawa Tengah"),
    PredefinedCity("Kudus", -6.8048, 110.8405, "ID", "Jawa Tengah"),
    PredefinedCity("Klaten", -7.7056, 110.6044, "ID", "Jawa Tengah"),

    // === INDONESIA (JAWA TIMUR) ===
    PredefinedCity("Surabaya", -7.2575, 112.7521, "ID", "Jawa Timur"),
    PredefinedCity("Malang", -7.9666, 112.6326, "ID", "Jawa Timur"),
    PredefinedCity("Sidoarjo", -7.4478, 112.7183, "ID", "Jawa Timur"),
    PredefinedCity("Gresik", -7.1566, 112.6555, "ID", "Jawa Timur"),
    PredefinedCity("Kediri", -7.8480, 112.0178, "ID", "Jawa Timur"),
    PredefinedCity("Madiun", -7.6298, 111.5239, "ID", "Jawa Timur"),
    PredefinedCity("Jember", -8.1724, 113.7007, "ID", "Jawa Timur"),
    PredefinedCity("Banyuwangi", -8.2192, 114.3692, "ID", "Jawa Timur"),
    PredefinedCity("Batu", -7.8712, 112.5271, "ID", "Jawa Timur"),
    PredefinedCity("Blitar", -8.0983, 112.1681, "ID", "Jawa Timur"),
    PredefinedCity("Probolinggo", -7.7543, 113.2159, "ID", "Jawa Timur"),
    PredefinedCity("Pasuruan", -7.6453, 112.9075, "ID", "Jawa Timur"),

    // === INDONESIA (SUMATERA) ===
    PredefinedCity("Banda Aceh", 5.5483, 95.3238, "ID", "Aceh"),
    PredefinedCity("Medan", 3.5952, 98.6722, "ID", "Sumatera Utara"),
    PredefinedCity("Padang", -0.9471, 100.4172, "ID", "Sumatera Barat"),
    PredefinedCity("Pekanbaru", 0.5071, 101.4478, "ID", "Riau"),
    PredefinedCity("Batam", 1.1301, 104.0529, "ID", "Kepulauan Riau"),
    PredefinedCity("Tanjung Pinang", 0.9167, 104.4500, "ID", "Kepulauan Riau"),
    PredefinedCity("Jambi", -1.6101, 103.6131, "ID", "Jambi"),
    PredefinedCity("Palembang", -2.9761, 104.7754, "ID", "Sumatera Selatan"),
    PredefinedCity("Bengkulu", -3.8004, 102.2655, "ID", "Bengkulu"),
    PredefinedCity("Bandar Lampung", -5.4500, 105.2667, "ID", "Lampung"),
    PredefinedCity("Pangkal Pinang", -2.1333, 106.1167, "ID", "Bangka Belitung"),

    // === INDONESIA (BALI & NUSA TENGGARA) ===
    PredefinedCity("Denpasar", -8.6705, 115.2126, "ID", "Bali"),
    PredefinedCity("Mataram", -8.5833, 116.1167, "ID", "Nusa Tenggara Barat"),
    PredefinedCity("Bima", -8.4608, 118.7267, "ID", "Nusa Tenggara Barat"),
    PredefinedCity("Kupang", -10.1772, 123.6070, "ID", "Nusa Tenggara Timur"),
    PredefinedCity("Labuan Bajo", -8.4964, 119.8877, "ID", "Nusa Tenggara Timur"),

    // === INDONESIA (KALIMANTAN) ===
    PredefinedCity("Pontianak", -0.0263, 109.3425, "ID", "Kalimantan Barat"),
    PredefinedCity("Banjarmasin", -3.3194, 114.5908, "ID", "Kalimantan Selatan"),
    PredefinedCity("Banjarbaru", -3.4400, 114.8300, "ID", "Kalimantan Selatan"),
    PredefinedCity("Balikpapan", -1.2379, 116.8529, "ID", "Kalimantan Timur"),
    PredefinedCity("Samarinda", -0.5022, 117.1536, "ID", "Kalimantan Timur"),
    PredefinedCity("IKN Nusantara", -0.9733, 116.7088, "ID", "Nusantara"),
    PredefinedCity("Palangka Raya", -2.2161, 113.9139, "ID", "Kalimantan Tengah"),
    PredefinedCity("Tarakan", 3.3000, 117.6333, "ID", "Kalimantan Utara"),

    // === INDONESIA (SULAWESI) ===
    PredefinedCity("Makassar", -5.1477, 119.4327, "ID", "Sulawesi Selatan"),
    PredefinedCity("Manado", 1.4748, 124.8421, "ID", "Sulawesi Utara"),
    PredefinedCity("Palu", -0.9003, 119.8779, "ID", "Sulawesi Tengah"),
    PredefinedCity("Kendari", -3.9985, 122.5126, "ID", "Sulawesi Tenggara"),
    PredefinedCity("Gorontalo", 0.5435, 123.0568, "ID", "Gorontalo"),
    PredefinedCity("Mamuju", -2.6748, 118.8882, "ID", "Sulawesi Barat"),

    // === INDONESIA (MALUKU & PAPUA) ===
    PredefinedCity("Ambon", -3.6954, 128.1814, "ID", "Maluku"),
    PredefinedCity("Ternate", 0.7833, 127.3667, "ID", "Maluku Utara"),
    PredefinedCity("Jayapura", -2.5337, 140.7181, "ID", "Papua"),
    PredefinedCity("Sorong", -0.8762, 131.2558, "ID", "Papua Barat Daya"),
    PredefinedCity("Merauke", -8.4991, 140.4011, "ID", "Papua Selatan"),
    PredefinedCity("Timika", -4.5448, 136.8872, "ID", "Papua Tengah"),

    // === ARAB SAUDI 🇸🇦 ===
    PredefinedCity("Makkah", 21.3891, 39.8579, "SA", "Makkah"),
    PredefinedCity("Madinah", 24.5247, 39.5692, "SA", "Madinah"),
    PredefinedCity("Riyadh", 24.7136, 46.6753, "SA", "Riyadh"),
    PredefinedCity("Jeddah", 21.4858, 39.1925, "SA", "Makkah"),
    PredefinedCity("Dammam", 26.4207, 50.0888, "SA", "Eastern Province"),

    // === MALAYSIA 🇲🇾 ===
    PredefinedCity("Kuala Lumpur", 3.1390, 101.6869, "MY", "Federal Territory"),
    PredefinedCity("Penang (George Town)", 5.4141, 100.3288, "MY", "Penang"),
    PredefinedCity("Johor Bahru", 1.4927, 103.7414, "MY", "Johor"),
    PredefinedCity("Kota Kinabalu", 5.9804, 116.0735, "MY", "Sabah"),
    PredefinedCity("Kuching", 1.5533, 110.3592, "MY", "Sarawak"),
    PredefinedCity("Shah Alam", 3.0738, 101.5183, "MY", "Selangor"),

    // === SINGAPURA 🇸🇬 ===
    PredefinedCity("Singapore", 1.3521, 103.8198, "SG", "Singapore"),

    // === TURKI 🇹🇷 ===
    PredefinedCity("Istanbul", 41.0082, 28.9784, "TR", "Istanbul"),
    PredefinedCity("Ankara", 39.9334, 32.8597, "TR", "Ankara"),
    PredefinedCity("Izmir", 38.4237, 27.1428, "TR", "Izmir"),

    // === MESIR 🇪🇬 ===
    PredefinedCity("Kairo (Cairo)", 30.0444, 31.2357, "EG", "Cairo"),
    PredefinedCity("Alexandria", 31.2001, 29.9187, "EG", "Alexandria"),

    // === UNI EMIRAT ARAB 🇦🇪 ===
    PredefinedCity("Dubai", 25.2048, 55.2708, "AE", "Dubai"),
    PredefinedCity("Abu Dhabi", 24.4539, 54.3773, "AE", "Abu Dhabi"),

    // === INGGRIS (UK) 🇬🇧 ===
    PredefinedCity("London", 51.5074, -0.1278, "GB", "Greater London"),
    PredefinedCity("Manchester", 53.4808, -2.2426, "GB", "Greater Manchester"),

    // === AMERIKA SERIKAT 🇺🇸 ===
    PredefinedCity("New York", 40.7128, -74.0060, "US", "New York"),
    PredefinedCity("Los Angeles", 34.0522, -118.2437, "US", "California"),
    PredefinedCity("Chicago", 41.8781, -87.6298, "US", "Illinois"),

    // === JEPANG 🇯🇵 ===
    PredefinedCity("Tokyo", 35.6762, 139.6503, "JP", "Kanto"),
    PredefinedCity("Osaka", 34.6937, 135.5023, "JP", "Kansai"),

    // === AUSTRALIA 🇦🇺 ===
    PredefinedCity("Sydney", -33.8688, 151.2093, "AU", "New South Wales"),
    PredefinedCity("Melbourne", -37.8136, 144.9631, "AU", "Victoria"),
    PredefinedCity("Perth", -31.9505, 115.8605, "AU", "Western Australia"),

    // === JERMAN 🇩🇪 ===
    PredefinedCity("Berlin", 52.5200, 13.4050, "DE", "Berlin"),
    PredefinedCity("Frankfurt", 50.1109, 8.6821, "DE", "Hesse")
)

val defaultIndonesianCities = allPredefinedCities.filter { it.countryCode == "ID" }
