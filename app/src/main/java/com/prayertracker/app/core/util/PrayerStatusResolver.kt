package com.prayertracker.app.core.util

import com.prayertracker.app.core.model.PrayerStatus

/**
 * Sumber kebenaran tunggal (Single Source of Truth) untuk resolusi status salat,
 * keterangan (Awal Waktu, Tepat Waktu, Akhir Waktu, Qadha Selesai, Terlewat),
 * serta generator formula dinamis Google Sheets yang konsisten.
 */
object PrayerStatusResolver {

    const val EARLY_WINDOW_MS: Long = 30 * 60 * 1000L  // 30 menit
    const val LATE_WINDOW_MS: Long  = 15 * 60 * 1000L  // 15 menit

    /**
     * Menentukan keterangan status salat secara konsisten di seluruh aplikasi:
     * - completedAt <= scheduledTime + 30m -> "Tepat Waktu (Awal Waktu)"
     * - completedAt <= effEnd - 15m        -> "Tepat Waktu"
     * - completedAt <= effEnd              -> "Tepat Waktu (Akhir Waktu)"
     * - completedAt > effEnd               -> "Qadha Selesai"
     * - MISSED                             -> "Terlewat (Belum Qadha)"
     * - else                               -> "Belum Salat"
     */
    fun resolveKeterangan(
        status: PrayerStatus,
        completedAtEpoch: Long?,
        scheduledTimeEpoch: Long,
        endTimeEpoch: Long
    ): String {
        return when (status) {
            PrayerStatus.COMPLETED -> {
                val doneAt = completedAtEpoch ?: scheduledTimeEpoch
                val effEnd = PrayerDateTimeUtils.calculateEffectiveEndTime(scheduledTimeEpoch, endTimeEpoch)
                when {
                    doneAt <= scheduledTimeEpoch + EARLY_WINDOW_MS -> "Tepat Waktu (Awal Waktu)"
                    doneAt <= effEnd - LATE_WINDOW_MS -> "Tepat Waktu"
                    doneAt <= effEnd -> "Tepat Waktu (Akhir Waktu)"
                    else -> "Qadha Selesai"
                }
            }
            PrayerStatus.QADHA_COMPLETED -> "Qadha Selesai"
            PrayerStatus.MISSED -> "Terlewat (Belum Qadha)"
            else -> "Belum Salat"
        }
    }

    /**
     * Formula dinamis Google Sheets untuk Kolom G (Status Ibadah):
     * Otomatis "Belum" jika Jam Selesai (F) kosong atau "-", dan "Sudah" jika terisi.
     */
    fun buildGoogleSheetsStatusFormula(row: Int, sep: String, s: String = "$"): String {
        return "=IF(OR(${s}F$row=\"\"$sep ${s}F$row=\"-\")$sep \"Belum\"$sep \"Sudah\")"
    }

    /**
     * Formula dinamis Google Sheets untuk Kolom H (Keterangan):
     * Menggunakan LET() yang membandingkan Tanggal (B), Jadwal Masuk (D), Batas Akhir (E), dan Jam Selesai (F).
     */
    fun buildGoogleSheetsKeteranganFormula(row: Int, sep: String, s: String = "$"): String {
        return "=LET(" +
            "dVal$sep IF(ISNUMBER(${s}B$row)$sep ${s}B$row$sep IFERROR(DATEVALUE(${s}B$row)$sep 0))$sep " +
            "sVal$sep IF(ISNUMBER(${s}D$row)$sep ${s}D$row$sep IFERROR(TIMEVALUE(${s}D$row)$sep 0))$sep " +
            "eVal$sep IF(ISNUMBER(${s}E$row)$sep ${s}E$row$sep IFERROR(TIMEVALUE(${s}E$row)$sep 0))$sep " +
            "sched$sep dVal + sVal$sep " +
            "deadline$sep dVal + eVal + IF(eVal < sVal$sep 1$sep 0)$sep " +
            "done$sep IF(ISNUMBER(${s}F$row)$sep IF(${s}F$row < 1$sep dVal + ${s}F$row$sep ${s}F$row)$sep IFERROR(DATEVALUE(${s}F$row) + TIMEVALUE(${s}F$row)$sep 0))$sep " +
            "IF(OR(${s}F$row=\"\"$sep ${s}F$row=\"-\")$sep " +
            "IF(NOW() > deadline$sep \"Terlewat (Belum Qadha)\"$sep \"Belum Salat\")$sep " +
            "IF(OR(ISNUMBER(SEARCH(\"Qadha\"$sep \"\" & ${s}F$row))$sep done > deadline)$sep \"Qadha Selesai\"$sep " +
            "IF(done <= sched + (30/1440)$sep \"Tepat Waktu (Awal Waktu)\"$sep " +
            "IF(done <= deadline - (15/1440)$sep \"Tepat Waktu\"$sep \"Tepat Waktu (Akhir Waktu)\")))))"
    }
}
