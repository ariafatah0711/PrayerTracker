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
                writer.write("Tanggal,Nama Salat,Jadwal Masuk,Batas Akhir,Status,Waktu Konfirmasi\n")

                prayers.forEach { p ->
                    val line = listOf(
                        p.prayerDate,
                        p.prayerName.displayName,
                        formatEpoch(p.scheduledTimeEpoch),
                        formatEpoch(p.endTimeEpoch),
                        p.status.displayName,
                        formatEpoch(p.completedAtEpoch)
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

            database.prayerRecordDao().insertAll(entities)
            Result.success(entities.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
