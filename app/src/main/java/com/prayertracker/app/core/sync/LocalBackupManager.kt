package com.prayertracker.app.core.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.prayertracker.app.core.database.AppDatabase
import com.prayertracker.app.core.database.entity.PrayerRecordEntity
import com.prayertracker.app.core.model.PrayerName
import com.prayertracker.app.core.model.PrayerStatus
import com.prayertracker.app.core.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LocalBackupManager(private val database: AppDatabase) {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())

    companion object {
        private const val EARLY_WINDOW_MS = 30 * 60 * 1000L  // 30 menit
        private const val LATE_WINDOW_MS  = 15 * 60 * 1000L  // 15 menit
    }

    /**
     * Menentukan keterangan berdasarkan jam selesai relatif terhadap jadwal masuk dan batas akhir.
     */
    private fun resolveKeterangan(
        status: PrayerStatus,
        completedAtEpoch: Long?,
        scheduledTimeEpoch: Long,
        endTimeEpoch: Long
    ): String {
        return when (status) {
            PrayerStatus.COMPLETED -> {
                val doneAt = completedAtEpoch ?: scheduledTimeEpoch
                when {
                    doneAt <= scheduledTimeEpoch + EARLY_WINDOW_MS -> "Tepat Waktu (Awal Waktu)"
                    doneAt <= endTimeEpoch - LATE_WINDOW_MS -> "Tepat Waktu"
                    doneAt <= endTimeEpoch -> "Tepat Waktu (Akhir Waktu)"
                    else -> "Qadha Selesai"
                }
            }
            PrayerStatus.QADHA_COMPLETED -> "Qadha Selesai"
            PrayerStatus.MISSED -> "Terlewat (Belum Qadha)"
            else -> "Belum Salat"
        }
    }

    private fun formatEpoch(epoch: Long?): String {
        if (epoch == null || epoch <= 0) return "-"
        return try {
            timeFormatter.format(Instant.ofEpochMilli(epoch))
        } catch (_: Exception) {
            "-"
        }
    }

    /**
     * Ekspor seluruh riwayat salat ke file CSV yang kompatibel 100% dengan
     * Microsoft Excel, Google Sheets, dan LibreOffice Calc.
     */
    suspend fun exportToCsv(context: Context): Result<Intent> = withContext(Dispatchers.IO) {
        try {
            val prayers = database.prayerRecordDao().getAllPrayersFlow().first()
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val csvFile = File(exportDir, "prayer_tracker_riwayat_salat.csv")

            FileOutputStream(csvFile).bufferedWriter().use { writer ->
                // UTF-8 BOM agar dibuka di Excel Windows langsung rapi tanpa encoding error
                writer.write("\uFEFF")

                // BAGIAN 1: REKAP MATRIKS HARIAN (OVERVIEW)
                writer.write("--- REKAP HARIAN IBADAH SALAT ---\n")
                writer.write("Tanggal,Subuh,Dzuhur,Ashar,Maghrib,Isya,Total Selesai\n")

                val dates = prayers.map { it.prayerDate }.distinct().sortedDescending()
                val prayersByDate = prayers.groupBy { it.prayerDate }

                fun formatDateNice(dStr: String): String {
                    return try {
                        val parsed = java.time.LocalDate.parse(dStr)
                        parsed.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale("id", "ID")))
                    } catch (_: Exception) {
                        dStr
                    }
                }

                fun statusText(p: PrayerRecordEntity?): String {
                    if (p == null) return "Belum"
                    val isDone = p.status == PrayerStatus.COMPLETED || p.status == PrayerStatus.QADHA_COMPLETED
                    if (!isDone) return "Belum"
                    val time = formatEpoch(p.completedAtEpoch ?: p.scheduledTimeEpoch)
                    return if (p.status == PrayerStatus.QADHA_COMPLETED) "Sudah ($time Qadha)" else "Sudah ($time)"
                }

                fun isDone(p: PrayerRecordEntity?): Boolean {
                    return p?.status == PrayerStatus.COMPLETED || p?.status == PrayerStatus.QADHA_COMPLETED
                }

                dates.forEach { date ->
                    val dayPrayers = prayersByDate[date] ?: emptyList()
                    val fajr = dayPrayers.find { it.prayerName == PrayerName.FAJR }
                    val dhuhr = dayPrayers.find { it.prayerName == PrayerName.DHUHR }
                    val asr = dayPrayers.find { it.prayerName == PrayerName.ASR }
                    val maghrib = dayPrayers.find { it.prayerName == PrayerName.MAGHRIB }
                    val isha = dayPrayers.find { it.prayerName == PrayerName.ISHA }

                    val doneCount = listOf(fajr, dhuhr, asr, maghrib, isha).count { isDone(it) }

                    val row = listOf(
                        "\"${formatDateNice(date)}\"",
                        "\"${statusText(fajr)}\"",
                        "\"${statusText(dhuhr)}\"",
                        "\"${statusText(asr)}\"",
                        "\"${statusText(maghrib)}\"",
                        "\"${statusText(isha)}\"",
                        "\"$doneCount / 5\""
                    ).joinToString(",")
                    writer.write(row)
                    writer.newLine()
                }

                // Baris pemisah
                writer.newLine()
                writer.write("--- LOG DETAIL DATA MENTAH PER SALAT ---\n")
                writer.write("No,Tanggal,Salat,Jadwal Masuk,Batas Akhir,Jam Selesai,Status Ibadah,Keterangan\n")

                prayers.forEachIndexed { idx, p ->
                    val isDone = p.status == PrayerStatus.COMPLETED || p.status == PrayerStatus.QADHA_COMPLETED
                    val statusIbadah = if (isDone) "Sudah" else "Belum"
                    val jamSelesai = if (isDone) formatEpoch(p.completedAtEpoch ?: p.scheduledTimeEpoch) else "-"
                    val keterangan = resolveKeterangan(
                        status = p.status,
                        completedAtEpoch = p.completedAtEpoch,
                        scheduledTimeEpoch = p.scheduledTimeEpoch,
                        endTimeEpoch = p.endTimeEpoch
                    )

                    val line = listOf(
                        "${idx + 1}",
                        "\"${formatDateNice(p.prayerDate)}\"",
                        p.prayerName.displayName,
                        formatEpoch(p.scheduledTimeEpoch),
                        formatEpoch(p.endTimeEpoch),
                        jamSelesai,
                        statusIbadah,
                        "\"$keterangan\""
                    ).joinToString(",")
                    writer.write(line)
                    writer.newLine()
                }
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                csvFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Riwayat Salat Prayer Tracker")
                putExtra(Intent.EXTRA_TEXT, "File ekspor riwayat salat dari aplikasi Prayer Tracker.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            Result.success(Intent.createChooser(shareIntent, "Buka atau Bagikan File Excel / CSV"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Cadangkan seluruh database ke file JSON terenkapsulasi yang bisa disimpan di Drive / WA / Penyimpanan.
     */
    suspend fun exportBackupJson(context: Context): Result<Intent> = withContext(Dispatchers.IO) {
        try {
            val prayers = database.prayerRecordDao().getAllPrayersFlow().first()
            val root = JSONObject().apply {
                put("version", 1)
                put("exported_at", System.currentTimeMillis())
                put("app", "PrayerTracker")
            }

            val prayerArray = JSONArray()
            prayers.forEach { p ->
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("user_id", p.userId)
                    put("prayer_name", p.prayerName.name)
                    put("prayer_date", p.prayerDate)
                    put("scheduled_time_epoch", p.scheduledTimeEpoch)
                    put("end_time_epoch", p.endTimeEpoch)
                    put("status", p.status.name)
                    put("completed_at_epoch", p.completedAtEpoch ?: JSONObject.NULL)
                }
                prayerArray.put(obj)
            }
            root.put("prayers", prayerArray)

            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val jsonFile = File(exportDir, "prayer_tracker_backup.json")
            jsonFile.writeText(root.toString(2))

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                jsonFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Backup Database Prayer Tracker")
                putExtra(Intent.EXTRA_TEXT, "File cadangan database Prayer Tracker.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            Result.success(Intent.createChooser(shareIntent, "Simpan File Cadangan Database"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Pulihkan data dari file JSON yang dipilih pengguna dari memori HP.
     */
    suspend fun restoreFromJsonUri(context: Context, uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: return@withContext Result.failure(IllegalStateException("Tidak dapat membaca file backup"))

            val root = JSONObject(content)
            val prayersArray = root.optJSONArray("prayers")
                ?: return@withContext Result.failure(IllegalStateException("Format file backup tidak valid"))

            val entities = mutableListOf<PrayerRecordEntity>()
            for (i in 0 until prayersArray.length()) {
                val obj = prayersArray.getJSONObject(i)
                val prayer = PrayerRecordEntity(
                    id = obj.getString("id"),
                    userId = obj.optString("user_id", "local_user"),
                    prayerName = PrayerName.valueOf(obj.getString("prayer_name")),
                    prayerDate = obj.getString("prayer_date"),
                    scheduledTimeEpoch = obj.getLong("scheduled_time_epoch"),
                    endTimeEpoch = obj.getLong("end_time_epoch"),
                    status = PrayerStatus.valueOf(obj.getString("status")),
                    completedAtEpoch = if (obj.isNull("completed_at_epoch")) null else obj.getLong("completed_at_epoch"),
                    syncStatus = SyncStatus.SYNCED
                )
                entities.add(prayer)
            }

            val ids = entities.map { it.id }
            database.qadhaRecordDao().deleteByPrayerRecordIds(ids)
            database.prayerRecordDao().insertAll(entities)

            // Pulihkan juga record qadha untuk salat yang statusnya QADHA_COMPLETED
            entities.forEach { p ->
                if (p.status == PrayerStatus.QADHA_COMPLETED) {
                    val existingQ = database.qadhaRecordDao().getByPrayerRecordId(p.id)
                    if (existingQ == null) {
                        database.qadhaRecordDao().insert(
                            com.prayertracker.app.core.database.entity.QadhaRecordEntity(
                                id = java.util.UUID.randomUUID().toString(),
                                prayerRecordId = p.id,
                                qadhaStatus = PrayerStatus.QADHA_COMPLETED,
                                qadhaAtEpoch = p.completedAtEpoch ?: p.scheduledTimeEpoch,
                                notes = "Dipulihkan dari File Cadangan",
                                syncStatus = SyncStatus.SYNCED
                            )
                        )
                    }
                }
            }

            Result.success(entities.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
