package com.prayertracker.app.core.sync

import org.json.JSONArray

/** Nama tab, range, dan header yang dimiliki aplikasi. */
object GoogleSheetsSheetSchema {
    const val DAILY = "Ringkasan Harian"
    const val WEEKLY = "Ringkasan Mingguan"
    const val TIME_RECAP = "Rekap Waktu"
    const val RAW_DATA = "Data Mentah"

    fun dailyHeader(): JSONArray = rowOf("Tanggal", "Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya", "Selesai")

    fun timeRecapHeader(): JSONArray = rowOf("Tanggal", "Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya", "Selesai")

    fun rawDataHeader(): JSONArray = rowOf(
        "ID", "Tanggal", "Salat", "Jadwal Masuk", "Batas Akhir", "Jam Selesai", "Status Ibadah", "Keterangan"
    )

    fun weeklyHeaderRows(): Pair<JSONArray, JSONArray> {
        val firstRow = JSONArray().apply {
            put("Bulan")
            put("Minggu")
            put("Rentang Tanggal")
            listOf("Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu").forEach { day ->
                put(day)
                repeat(4) { put("") }
            }
            put("Selesai")
        }
        val secondRow = JSONArray().apply {
            repeat(3) { put("") }
            repeat(7) { listOf("S", "D", "A", "M", "I").forEach(::put) }
            put("Target: 35")
        }
        return firstRow to secondRow
    }

    private fun rowOf(vararg values: String): JSONArray = JSONArray().apply { values.forEach(::put) }
}
