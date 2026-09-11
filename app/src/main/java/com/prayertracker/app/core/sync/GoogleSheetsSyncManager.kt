package com.prayertracker.app.core.sync

import com.prayertracker.app.core.database.AppDatabase
import com.prayertracker.app.core.database.entity.PrayerRecordEntity
import com.prayertracker.app.core.model.PrayerName
import com.prayertracker.app.core.model.PrayerStatus
import com.prayertracker.app.core.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.prayertracker.app.core.datastore.AppSettingsRepository
import kotlinx.coroutines.flow.first
import java.util.Locale

class GoogleSheetsSyncManager(
    private val database: AppDatabase,
    private val authManager: GoogleAuthManager,
    private val settingsRepository: AppSettingsRepository
) {
    private val httpClient = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private val SPREADSHEET_TITLE = "Prayer Tracker - Catatan Ibadah"
    private val SHEETS_API_BASE = "https://sheets.googleapis.com/v4/spreadsheets"
    private val DRIVE_FILES_BASE = "https://www.googleapis.com/drive/v3/files"

    private fun formatEpoch(epoch: Long?): String {
        if (epoch == null || epoch <= 0) return "-"
        return try {
            Instant.ofEpochMilli(epoch)
                .atZone(ZoneId.systemDefault())
                .format(timeFormatter)
        } catch (_: Exception) {
            "-"
        }
    }

    /**
     * Menentukan keterangan berdasarkan jam selesai relatif terhadap jadwal masuk dan batas akhir.
     *
     * - completedAt <= scheduledTime + 30 menit  â†’ "Tepat Waktu (Awal Waktu)"
     * - completedAt <= endTime - 15 menit        â†’ "Tepat Waktu"
     * - completedAt <= endTime                   â†’ "Tepat Waktu (Akhir Waktu)"
     * - completedAt > endTime                    â†’ "Qadha Selesai"
     * - MISSED                                   â†’ "Terlewat (Belum Qadha)"
     * - else                                     â†’ "Belum Salat"
     */
    companion object {
        private const val EARLY_WINDOW_MS = 30 * 60 * 1000L  // 30 menit
        private const val LATE_WINDOW_MS  = 15 * 60 * 1000L  // 15 menit
    }

    fun resolveKeterangan(
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
                    else -> "Qadha Selesai" // selesai tapi melewati batas akhir
                }
            }
            PrayerStatus.QADHA_COMPLETED -> "Qadha Selesai"
            PrayerStatus.MISSED -> "Terlewat (Belum Qadha)"
            else -> "Belum Salat"
        }
    }

    /**
     * Tarik data HANYA dari Google Sheets ke database lokal (Cloud -> HP).
     * Tidak akan menimpa atau menghapus perubahan yang dibuat user di Google Sheets!
     */
    suspend fun pullFromSheets(): Result<String> = withContext(Dispatchers.IO) {
        val token = authManager.getAccessToken()
            ?: return@withContext Result.failure(IllegalStateException("Not authenticated with Google"))

        try {
            val spreadsheetId = findOrCreateSpreadsheet(token)
            pullDataFromSheetsInternal(token, spreadsheetId)
            val webUrl = "https://docs.google.com/spreadsheets/d/$spreadsheetId"
            Result.success(webUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun pullDataFromSheetsInternal(token: String, spreadsheetId: String) {
        val remoteIds = mutableSetOf<String>()
        val remoteNamesAndDates = mutableSetOf<String>()
        val datesInSheet = mutableSetOf<String>()

        // 1. Tarik dari tab "Data Mentah" (Sumber data detail individual)
        try {
            val rawRange = java.net.URLEncoder.encode("'Data Mentah'!A2:H1000", "UTF-8")
            val getRowsUrl = "$SHEETS_API_BASE/$spreadsheetId/values/$rawRange"
            val getReq = Request.Builder()
                .url(getRowsUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(getReq).execute().use { getRes ->
                if (getRes.isSuccessful) {
                    val resJson = JSONObject(getRes.body?.string() ?: "")
                    val remoteValues = resJson.optJSONArray("values")
                    if (remoteValues != null && remoteValues.length() > 0) {
                        for (i in 0 until remoteValues.length()) {
                            val rRow = remoteValues.getJSONArray(i)
                            val rowId = rRow.optString(0, "").trim()
                            val rawDate = rRow.optString(1, "").trim()
                            val rDate = normalizeSheetDate(rawDate).ifBlank { rawDate }
                            val rSalatStr = rRow.optString(2, "").trim()
                            val pName = PrayerName.fromString(rSalatStr) ?: continue

                            if (rDate.isBlank()) continue

                            datesInSheet.add(rDate)
                            remoteNamesAndDates.add("${pName.name}_$rDate")
                            if (rowId.isNotBlank()) remoteIds.add(rowId)

                            val rJadwal = rRow.optString(3, "").trim()
                            val rBatas = rRow.optString(4, "").trim()
                            val rJamSelesai = rRow.optString(5, "").trim()
                            val rStatusIbadah = rRow.optString(6, "").trim()
                            val rKeterangan = rRow.optString(7, "").trim()

                            val isRemoteDone = rStatusIbadah.equals("Sudah", ignoreCase = true) ||
                                               rStatusIbadah.equals("Selesai", ignoreCase = true) ||
                                               rStatusIbadah.startsWith("Sudah", ignoreCase = true) ||
                                               rStatusIbadah.equals("Done", ignoreCase = true) ||
                                               rStatusIbadah.equals("Ya", ignoreCase = true) ||
                                               rStatusIbadah.contains("✓")

                            val isRemoteQadha = rKeterangan.contains("Qadha", ignoreCase = true) ||
                                               rJamSelesai.contains("Qadha", ignoreCase = true) ||
                                               rStatusIbadah.contains("Qadha", ignoreCase = true)
                            val nowEpoch = System.currentTimeMillis()

                            // Cari entitas lokal baik dengan ID maupun pasangan (Nama Salat + Tanggal)
                            val localPrayer = (if (rowId.isNotBlank()) database.prayerRecordDao().getPrayerById(rowId) else null)
                                ?: database.prayerRecordDao().getPrayerByNameAndDate(pName, rDate)

                            val targetId = rowId.ifBlank { localPrayer?.id ?: java.util.UUID.randomUUID().toString() }
                            remoteIds.add(targetId)

                            val parsedSched = parseTimeToEpoch(rDate, rJadwal)
                            val parsedEnd = parseTimeToEpoch(rDate, rBatas)
                            val schedEpoch = if (parsedSched > 0) parsedSched else (localPrayer?.scheduledTimeEpoch ?: nowEpoch)
                            val endEpoch = if (parsedEnd > 0) parsedEnd else (localPrayer?.endTimeEpoch ?: (schedEpoch + 3600000))

                            val targetStatus = if (isRemoteDone) {
                                if (isRemoteQadha) PrayerStatus.QADHA_COMPLETED else PrayerStatus.COMPLETED
                            } else {
                                if (nowEpoch > endEpoch) PrayerStatus.MISSED else PrayerStatus.PENDING
                            }

                            val targetCompletedAt: Long? = if (isRemoteDone) {
                                val parsedDone = parseTimeToEpoch(rDate, rJamSelesai)
                                if (parsedDone > 0) parsedDone else (localPrayer?.completedAtEpoch ?: schedEpoch)
                            } else {
                                null
                            }

                            if (localPrayer != null) {
                                database.prayerRecordDao().updateStatus(
                                    id = localPrayer.id,
                                    status = targetStatus,
                                    completedAt = targetCompletedAt,
                                    syncStatus = SyncStatus.SYNCED
                                )
                            } else {
                                // Buat entitas baru jika belum ada di database lokal
                                val newPrayer = PrayerRecordEntity(
                                    id = targetId,
                                    userId = "local_user",
                                    prayerName = pName,
                                    prayerDate = rDate,
                                    scheduledTimeEpoch = schedEpoch,
                                    endTimeEpoch = endEpoch,
                                    status = targetStatus,
                                    completedAtEpoch = targetCompletedAt,
                                    syncStatus = SyncStatus.SYNCED
                                )
                                database.prayerRecordDao().insert(newPrayer)
                            }

                            // Sinkronkan tabel qadha_records
                            if (targetStatus == PrayerStatus.QADHA_COMPLETED) {
                                val existingQ = database.qadhaRecordDao().getByPrayerRecordId(targetId)
                                if (existingQ == null) {
                                    database.qadhaRecordDao().insert(
                                        com.prayertracker.app.core.database.entity.QadhaRecordEntity(
                                            id = java.util.UUID.randomUUID().toString(),
                                            prayerRecordId = targetId,
                                            qadhaStatus = PrayerStatus.QADHA_COMPLETED,
                                            qadhaAtEpoch = targetCompletedAt ?: nowEpoch,
                                            notes = "Sinkron Google Sheets",
                                            syncStatus = SyncStatus.SYNCED
                                        )
                                    )
                                }
                            } else {
                                database.qadhaRecordDao().deleteByPrayerRecordId(targetId)
                            }
                        }

                        // Hanya hapus jika pengguna secara eksplisit menghapus baris di Google Sheets
                        if (remoteIds.isNotEmpty() && datesInSheet.isNotEmpty()) {
                            val localPrayers = database.prayerRecordDao().getAllPrayers()
                            val toDelete = localPrayers.filter {
                                it.prayerDate in datesInSheet &&
                                it.id !in remoteIds &&
                                "${it.prayerName.name}_${it.prayerDate}" !in remoteNamesAndDates
                            }
                            if (toDelete.isNotEmpty()) {
                                val deleteIds = toDelete.map { it.id }
                                database.qadhaRecordDao().deleteByPrayerRecordIds(deleteIds)
                                database.prayerRecordDao().deleteByIds(deleteIds)
                            }
                        }
                    }
                } else {
                    val err = getRes.body?.string() ?: ""
                    android.util.Log.e("SheetsSync", "Gagal membaca Data Mentah (${getRes.code}): $err")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Baca tab "Ringkasan" untuk menangkap editan langsung user pada tab pertama
        try {
            val ringkasanRange = java.net.URLEncoder.encode("'Ringkasan'!A2:F1000", "UTF-8")
            // Gunakan valueRenderOption=FORMULA agar kita tahu sel mana yang merupakan rumus bawaan vs editan manual user
            val ringkasanUrl = "$SHEETS_API_BASE/$spreadsheetId/values/$ringkasanRange?valueRenderOption=FORMULA"
            val ringkasanReq = Request.Builder()
                .url(ringkasanUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(ringkasanReq).execute().use { ringkasanRes ->
                if (ringkasanRes.isSuccessful) {
                    val rJson = JSONObject(ringkasanRes.body?.string() ?: "")
                    val rValues = rJson.optJSONArray("values")
                    if (rValues != null && rValues.length() > 0) {
                        val prayerNamesByCol = listOf(
                            PrayerName.FAJR,   // Col B (index 1)
                            PrayerName.DHUHR,  // Col C (index 2)
                            PrayerName.ASR,    // Col D (index 3)
                            PrayerName.MAGHRIB,// Col E (index 4)
                            PrayerName.ISHA    // Col F (index 5)
                        )
                        for (i in 0 until rValues.length()) {
                            val rRow = rValues.getJSONArray(i)
                            val rawDate = rRow.optString(0, "").trim()
                            if (rawDate.isBlank()) continue
                            val normalizedDate = normalizeSheetDate(rawDate).ifBlank { rawDate }
                            if (normalizedDate.isBlank()) continue

                            for (colIdx in 1..5) {
                                val cellValue = rRow.optString(colIdx, "").trim()
                                if (cellValue.isBlank()) continue

                                // PENTING: Jika sel diawali tanda '=', itu adalah rumus otomatis yang merujuk ke Data Mentah!
                                // Jangan ditimpa sebagai editan manual, karena Data Mentah sudah diproses di atas.
                                if (cellValue.startsWith("=")) continue

                                val pName = prayerNamesByCol[colIdx - 1]
                                val localPrayer = database.prayerRecordDao().getPrayerByNameAndDate(pName, normalizedDate)
                                    ?: continue

                                val isRingkasanDone = cellValue.equals("Sudah", ignoreCase = true) ||
                                                      cellValue.equals("Selesai", ignoreCase = true) ||
                                                      cellValue.equals("Done", ignoreCase = true) ||
                                                      cellValue.equals("Ya", ignoreCase = true) ||
                                                      cellValue.contains("✓")

                                val nowEpoch = System.currentTimeMillis()
                                if (isRingkasanDone) {
                                    database.prayerRecordDao().updateStatus(
                                        id = localPrayer.id,
                                        status = PrayerStatus.COMPLETED,
                                        completedAt = localPrayer.completedAtEpoch ?: nowEpoch,
                                        syncStatus = SyncStatus.SYNCED
                                    )
                                    database.qadhaRecordDao().deleteByPrayerRecordId(localPrayer.id)
                                } else if (cellValue.equals("Belum", ignoreCase = true) || cellValue.equals("Terlewat", ignoreCase = true)) {
                                    val revertStatus = if (nowEpoch > localPrayer.endTimeEpoch) PrayerStatus.MISSED else PrayerStatus.PENDING
                                    database.prayerRecordDao().updateStatus(
                                        id = localPrayer.id,
                                        status = revertStatus,
                                        completedAt = null,
                                        syncStatus = SyncStatus.SYNCED
                                    )
                                    database.qadhaRecordDao().deleteByPrayerRecordId(localPrayer.id)
                                }
                            }
                        }
                    }
                } else {
                    val err = ringkasanRes.body?.string() ?: ""
                    android.util.Log.e("SheetsSync", "Gagal membaca Ringkasan (${ringkasanRes.code}): $err")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun syncToSheets(skipRemotePull: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        val token = authManager.getAccessToken()
            ?: return@withContext Result.failure(IllegalStateException("Not authenticated with Google"))

        try {
            // 1. Cari atau buat spreadsheet
            val spreadsheetId = findOrCreateSpreadsheet(token)

            // 2. Pastikan 3 Sheet ada, hapus garis kisi, dan terapkan header bold & warna
            val sep = ensureSheetsAndFormatting(token, spreadsheetId)

            // 2b. Tarik perubahan dari Google Sheets jika tidak dilewati
            if (!skipRemotePull) {
                pullDataFromSheetsInternal(token, spreadsheetId)
            }

            // -----------------------------------------------------------------
            // Definisi Header Ketiga Sheet
            // -----------------------------------------------------------------
            val ringkasanHeader = JSONArray().apply {
                put("Tanggal")
                put("Subuh")
                put("Dzuhur")
                put("Ashar")
                put("Maghrib")
                put("Isya")
                put("Total Selesai")
            }
            val rekapWaktuHeader = JSONArray().apply {
                put("Tanggal")
                put("Subuh")
                put("Dzuhur")
                put("Ashar")
                put("Maghrib")
                put("Isya")
                put("Total Selesai")
            }
            val rawHeader = JSONArray().apply {
                put("ID")
                put("Tanggal")
                put("Salat")
                put("Jadwal Masuk")
                put("Batas Akhir")
                put("Jam Selesai")
                put("Status Ibadah")
                put("Keterangan")
            }

            // 3. Ambil seluruh data ibadah dari database lokal (termasuk hasil rekonsiliasi terbaru)
            val allPrayers = database.prayerRecordDao().getAllPrayers()
            if (allPrayers.isEmpty()) {
                // Inisialisasi header untuk ketiga sheet agar spreadsheet baru tidak kosong melompong
                try {
                    val initPayload = JSONObject().apply {
                        put("valueInputOption", "USER_ENTERED")
                        put("data", JSONArray().apply {
                            put(JSONObject().apply {
                                put("range", "'Ringkasan'!A1:G1")
                                put("majorDimension", "ROWS")
                                put("values", JSONArray().put(ringkasanHeader))
                            })
                            put(JSONObject().apply {
                                put("range", "'Rekap Waktu'!A1:G1")
                                put("majorDimension", "ROWS")
                                put("values", JSONArray().put(rekapWaktuHeader))
                            })
                            put(JSONObject().apply {
                                put("range", "'Data Mentah'!A1:H1")
                                put("majorDimension", "ROWS")
                                put("values", JSONArray().put(rawHeader))
                            })
                        })
                    }
                    val initReq = Request.Builder()
                        .url("$SHEETS_API_BASE/$spreadsheetId/values:batchUpdate")
                        .addHeader("Authorization", "Bearer $token")
                        .post(initPayload.toString().toRequestBody(jsonMediaType))
                        .build()
                    httpClient.newCall(initReq).execute().use { it.close() }
                } catch (_: Exception) {}

                val webUrl = "https://docs.google.com/spreadsheets/d/$spreadsheetId"
                return@withContext Result.success(webUrl)
            }

            // Urutkan tanggal secara menurun (hari ini paling atas)
            val dates = allPrayers.map { it.prayerDate }.distinct().sortedDescending()

            // -----------------------------------------------------------------
            // SHEET 1: "Ringkasan" (Overview Dinamis Terhubung Langsung ke Data Mentah)
            // -----------------------------------------------------------------
            val ringkasanRows = JSONArray()
            ringkasanRows.put(ringkasanHeader)

            val s = "$"
            dates.forEachIndexed { idx, date ->
                val r = idx + 2 // Baris 1-indexed di Google Sheets (Header di baris 1, data mulai baris 2)
                val row = JSONArray().apply {
                    put(date) // Kolom A (Tanggal)
                    put("=IF(COUNTIFS('Data Mentah'!${s}B:${s}B$sep ${s}A$r$sep 'Data Mentah'!${s}C:${s}C$sep \"Subuh\"$sep 'Data Mentah'!${s}G:${s}G$sep \"Sudah*\")>0$sep \"Sudah\"$sep \"Belum\")")
                    put("=IF(COUNTIFS('Data Mentah'!${s}B:${s}B$sep ${s}A$r$sep 'Data Mentah'!${s}C:${s}C$sep \"Dzuhur\"$sep 'Data Mentah'!${s}G:${s}G$sep \"Sudah*\")>0$sep \"Sudah\"$sep \"Belum\")")
                    put("=IF(COUNTIFS('Data Mentah'!${s}B:${s}B$sep ${s}A$r$sep 'Data Mentah'!${s}C:${s}C$sep \"Ashar\"$sep 'Data Mentah'!${s}G:${s}G$sep \"Sudah*\")>0$sep \"Sudah\"$sep \"Belum\")")
                    put("=IF(COUNTIFS('Data Mentah'!${s}B:${s}B$sep ${s}A$r$sep 'Data Mentah'!${s}C:${s}C$sep \"Maghrib\"$sep 'Data Mentah'!${s}G:${s}G$sep \"Sudah*\")>0$sep \"Sudah\"$sep \"Belum\")")
                    put("=IF(COUNTIFS('Data Mentah'!${s}B:${s}B$sep ${s}A$r$sep 'Data Mentah'!${s}C:${s}C$sep \"Isya\"$sep 'Data Mentah'!${s}G:${s}G$sep \"Sudah*\")>0$sep \"Sudah\"$sep \"Belum\")")
                    put("=COUNTIF(B$r:F$r$sep \"Sudah\") & \" / 5\"")
                }
                ringkasanRows.put(row)
            }

            // -----------------------------------------------------------------
            // SHEET 2: "Rekap Waktu" (Jam Selesai Dinamis Terhubung Langsung ke Data Mentah)
            // -----------------------------------------------------------------
            val rekapWaktuRows = JSONArray()
            rekapWaktuRows.put(rekapWaktuHeader)

            fun timeFormula(prayerName: String, r: Int): String {
                return "=IF(COUNTIFS('Data Mentah'!${s}B:${s}B$sep ${s}A$r$sep 'Data Mentah'!${s}C:${s}C$sep \"$prayerName\"$sep 'Data Mentah'!${s}G:${s}G$sep \"Sudah*\"$sep 'Data Mentah'!${s}F:${s}F$sep \"<>-\")>0$sep TEXT(INDEX(FILTER('Data Mentah'!${s}F:${s}F$sep 'Data Mentah'!${s}B:${s}B=${s}A$r$sep 'Data Mentah'!${s}C:${s}C=\"$prayerName\")$sep 1)$sep \"HH:mm\") & IF(COUNTIFS('Data Mentah'!${s}B:${s}B$sep ${s}A$r$sep 'Data Mentah'!${s}C:${s}C$sep \"$prayerName\"$sep 'Data Mentah'!${s}H:${s}H$sep \"*Qadha*\")>0$sep \" (Qadha)\"$sep \"\")$sep \"Belum\")"
            }

            dates.forEachIndexed { idx, date ->
                val r = idx + 2
                val row = JSONArray().apply {
                    put(date) // Kolom A (Tanggal)
                    put(timeFormula("Subuh", r))
                    put(timeFormula("Dzuhur", r))
                    put(timeFormula("Ashar", r))
                    put(timeFormula("Maghrib", r))
                    put(timeFormula("Isya", r))
                    put("=COUNTIF(B$r:F$r$sep \"<>Belum\") & \" / 5 Selesai\"")
                }
                rekapWaktuRows.put(row)
            }

            // -----------------------------------------------------------------
            // SHEET 3: "Data Mentah" (Rapi, Human-Friendly dengan ID Unik Teks)
            // -----------------------------------------------------------------
            val rawRows = JSONArray()
            rawRows.put(rawHeader)

            allPrayers.forEach { p ->
                val isDone = p.status == PrayerStatus.COMPLETED || p.status == PrayerStatus.QADHA_COMPLETED
                val statusIbadah = if (isDone) "Sudah" else "Belum"
                val jamSelesai = if (isDone) formatEpoch(p.completedAtEpoch ?: p.scheduledTimeEpoch) else "-"
                val keterangan = resolveKeterangan(
                    status = p.status,
                    completedAtEpoch = p.completedAtEpoch,
                    scheduledTimeEpoch = p.scheduledTimeEpoch,
                    endTimeEpoch = p.endTimeEpoch
                )

                val row = JSONArray().apply {
                    put(p.id)
                    put(p.prayerDate)
                    put(p.prayerName.displayName)
                    put(formatEpoch(p.scheduledTimeEpoch))
                    put(formatEpoch(p.endTimeEpoch))
                    put(jamSelesai)
                    put(statusIbadah)
                    put(keterangan)
                }
                rawRows.put(row)
            }

            // 4. Batch Clear area sheet terlebih dahulu agar tidak ada data lama yang tersisa
            try {
                val clearPayload = JSONObject().apply {
                    put("ranges", JSONArray().apply {
                        put("'Ringkasan'!A1:Z500")
                        put("'Rekap Waktu'!A1:Z500")
                        put("'Data Mentah'!A1:Z1000")
                    })
                }
                val clearReq = Request.Builder()
                    .url("$SHEETS_API_BASE/$spreadsheetId/values:batchClear")
                    .addHeader("Authorization", "Bearer $token")
                    .post(clearPayload.toString().toRequestBody(jsonMediaType))
                    .build()
                httpClient.newCall(clearReq).execute().close()
            } catch (_: Exception) {
                // Abaikan jika sheet baru
            }

            // 5. Batch Update values ketiga sheet sekaligus
            val updatePayload = JSONObject().apply {
                put("valueInputOption", "USER_ENTERED")
                put("data", JSONArray().apply {
                    put(JSONObject().apply {
                        put("range", "'Ringkasan'!A1:G${ringkasanRows.length()}")
                        put("majorDimension", "ROWS")
                        put("values", ringkasanRows)
                    })
                    put(JSONObject().apply {
                        put("range", "'Rekap Waktu'!A1:G${rekapWaktuRows.length()}")
                        put("majorDimension", "ROWS")
                        put("values", rekapWaktuRows)
                    })
                    put(JSONObject().apply {
                        put("range", "'Data Mentah'!A1:H${rawRows.length()}")
                        put("majorDimension", "ROWS")
                        put("values", rawRows)
                    })
                })
            }

            val updateUrl = "$SHEETS_API_BASE/$spreadsheetId/values:batchUpdate"
            val updateRequest = Request.Builder()
                .url(updateUrl)
                .addHeader("Authorization", "Bearer $token")
                .post(updatePayload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(updateRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    return@withContext Result.failure(Exception("Gagal update Google Sheet (${response.code}): $errBody"))
                }
            }

            val webUrl = "https://docs.google.com/spreadsheets/d/$spreadsheetId"
            Result.success(webUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun findOrCreateSpreadsheet(token: String): String {
        // 0. Prioritaskan spreadsheet yang URL-nya sudah tercatat di aplikasi jika masih valid
        try {
            val savedUrl = settingsRepository.settingsFlow.first().spreadsheetUrl
            if (!savedUrl.isNullOrBlank()) {
                val candidateId = savedUrl.substringAfter("/d/").substringBefore("/").substringBefore("?").trim()
                if (candidateId.isNotBlank() && candidateId != savedUrl) {
                    val verifyReq = Request.Builder()
                        .url("$SHEETS_API_BASE/$candidateId?fields=spreadsheetId")
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()
                    val isValid = try {
                        httpClient.newCall(verifyReq).execute().use { it.isSuccessful }
                    } catch (_: Exception) { false }
                    if (isValid) {
                        return candidateId
                    }
                }
            }
        } catch (_: Exception) {}

        // 1. Cari file spreadsheet di Google Drive, urutkan dari yang paling baru diubah oleh user (modifiedTime desc)
        val query = "name = '$SPREADSHEET_TITLE' and mimeType = 'application/vnd.google-apps.spreadsheet' and trashed = false"
        val url = "$DRIVE_FILES_BASE?q=${java.net.URLEncoder.encode(query, "UTF-8")}&orderBy=modifiedTime%20desc"

        val searchRequest = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            httpClient.newCall(searchRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    val files = json.optJSONArray("files")
                    if (files != null && files.length() > 0) {
                        for (i in 0 until files.length()) {
                            val candidateId = files.getJSONObject(i).getString("id")
                            // Verifikasi apakah spreadsheet ini benar-benar ada dan belum dihapus permanen dari Sheets
                            val verifyReq = Request.Builder()
                                .url("$SHEETS_API_BASE/$candidateId?fields=spreadsheetId")
                                .addHeader("Authorization", "Bearer $token")
                                .get()
                                .build()
                            val isValid = try {
                                httpClient.newCall(verifyReq).execute().use { it.isSuccessful }
                            } catch (_: Exception) {
                                false
                            }
                            if (isValid) {
                                return candidateId
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Lanjut buat baru jika pencarian gagal
        }

        // Buat spreadsheet baru dengan payload standar resmi Google Sheets API v4
        val createPayload = JSONObject().apply {
            put("properties", JSONObject().apply {
                put("title", SPREADSHEET_TITLE)
            })
        }

        val createRequest = Request.Builder()
            .url(SHEETS_API_BASE)
            .addHeader("Authorization", "Bearer $token")
            .post(createPayload.toString().toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(createRequest).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "")
                return json.getString("spreadsheetId")
            } else {
                val errBody = response.body?.string() ?: ""
                throw Exception("Gagal membuat spreadsheet (${response.code}): $errBody")
            }
        }
    }

    private fun ensureSheetsAndFormatting(token: String, spreadsheetId: String): String {
        var sep = ";"
        try {
            val getUrl = "$SHEETS_API_BASE/$spreadsheetId?fields=properties(locale),sheets(properties,conditionalFormats)"
            val request = Request.Builder()
                .url(getUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val json = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return sep
                JSONObject(response.body?.string() ?: "")
            }
            val spreadsheetLocale = json.optJSONObject("properties")?.optString("locale", "id_ID") ?: "id_ID"
            sep = if (spreadsheetLocale.startsWith("en", ignoreCase = true)) "," else ";"
            val sheetsArray = json.optJSONArray("sheets") ?: JSONArray()

            val existingTitles = mutableListOf<String>()
            var firstSheetId: Int? = null
            var ringkasanSheetId: Int? = null
            var rekapWaktuSheetId: Int? = null
            var dataMentahSheetId: Int? = null
            var ringkasanHasConditionalFormatting = false

            for (i in 0 until sheetsArray.length()) {
                val sheetObj = sheetsArray.getJSONObject(i)
                val props = sheetObj.getJSONObject("properties")
                val title = props.getString("title")
                val sheetId = props.getInt("sheetId")
                existingTitles.add(title)
                if (i == 0) firstSheetId = sheetId
                if (title == "Ringkasan") {
                    ringkasanSheetId = sheetId
                    val cFormats = sheetObj.optJSONArray("conditionalFormats")
                    if (cFormats != null && cFormats.length() > 0) {
                        ringkasanHasConditionalFormatting = true
                    }
                }
                if (title == "Rekap Waktu") rekapWaktuSheetId = sheetId
                if (title == "Data Mentah") dataMentahSheetId = sheetId
            }

            val batchRequests = JSONArray()

            // 1. Rename sheet pertama jika belum bernama "Ringkasan"
            if (!existingTitles.contains("Ringkasan") && firstSheetId != null) {
                batchRequests.put(JSONObject().apply {
                    put("updateSheetProperties", JSONObject().apply {
                        put("properties", JSONObject().apply {
                            put("sheetId", firstSheetId)
                            put("title", "Ringkasan")
                        })
                        put("fields", "title")
                    })
                })
                ringkasanSheetId = firstSheetId
            }

            // 2. Tambah "Rekap Waktu" jika belum ada
            if (!existingTitles.contains("Rekap Waktu")) {
                rekapWaktuSheetId = 101
                batchRequests.put(JSONObject().apply {
                    put("addSheet", JSONObject().apply {
                        put("properties", JSONObject().apply {
                            put("sheetId", rekapWaktuSheetId)
                            put("title", "Rekap Waktu")
                        })
                    })
                })
            }

            // 3. Tambah "Data Mentah" jika belum ada
            if (!existingTitles.contains("Data Mentah")) {
                dataMentahSheetId = 102
                batchRequests.put(JSONObject().apply {
                    put("addSheet", JSONObject().apply {
                        put("properties", JSONObject().apply {
                            put("sheetId", dataMentahSheetId)
                            put("title", "Data Mentah")
                        })
                    })
                })
            }

            // 4. Hapus Garis Kisi (hideGridlines: true) & Bekukan Baris Header (frozenRowCount: 1)
            val allSheetIds = listOfNotNull(ringkasanSheetId, rekapWaktuSheetId, dataMentahSheetId)
            allSheetIds.forEach { sId ->
                batchRequests.put(JSONObject().apply {
                    put("updateSheetProperties", JSONObject().apply {
                        put("properties", JSONObject().apply {
                            put("sheetId", sId)
                            put("gridProperties", JSONObject().apply {
                                put("frozenRowCount", 1)
                                put("hideGridlines", true)
                            })
                        })
                        put("fields", "gridProperties.frozenRowCount,gridProperties.hideGridlines")
                    })
                })
            }

            // 5. Header Styling: Background Hijau Emerald Elegan (#1B4D3E), Teks Putih Tebal (Bold), Rata Tengah
            // Ringkasan: 7 kolom (0..7)
            ringkasanSheetId?.let { sId ->
                batchRequests.put(createHeaderFormatRequest(sId, 7))
                batchRequests.put(createCenterAlignmentRequest(sId, 7))
                batchRequests.put(createDateFormatRequest(sId, 0)) // Kolom Tanggal (indeks 0) format "d MMMM yyyy"
                batchRequests.put(createColumnWidthRequest(sId, 0, 140)) // Tanggal lebih lebar
                for (col in 1..5) {
                    batchRequests.put(createColumnWidthRequest(sId, col, 95))
                }
                batchRequests.put(createColumnWidthRequest(sId, 6, 115))
            }

            // Rekap Waktu: 7 kolom (0..7)
            rekapWaktuSheetId?.let { sId ->
                batchRequests.put(createHeaderFormatRequest(sId, 7))
                batchRequests.put(createCenterAlignmentRequest(sId, 7))
                batchRequests.put(createDateFormatRequest(sId, 0))
                batchRequests.put(createColumnWidthRequest(sId, 0, 140))
                for (col in 1..5) {
                    batchRequests.put(createColumnWidthRequest(sId, col, 95))
                    batchRequests.put(createTextFormatRequest(sId, col))
                }
                batchRequests.put(createColumnWidthRequest(sId, 6, 115))
            }

            // Data Mentah: 8 kolom (0..8)
            dataMentahSheetId?.let { sId ->
                batchRequests.put(createHeaderFormatRequest(sId, 8))
                batchRequests.put(createCenterAlignmentRequest(sId, 8))
                batchRequests.put(createTextFormatRequest(sId, 0)) // Kolom ID di indeks 0 adalah teks murni, bukan tanggal!
                batchRequests.put(createDateFormatRequest(sId, 1)) // Kolom Tanggal di indeks 1
                batchRequests.put(createColumnWidthRequest(sId, 0, 180))  // ID
                batchRequests.put(createColumnWidthRequest(sId, 1, 140)) // Tanggal
                batchRequests.put(createColumnWidthRequest(sId, 2, 85))  // Salat
                batchRequests.put(createColumnWidthRequest(sId, 3, 95))  // Jadwal Masuk
                batchRequests.put(createColumnWidthRequest(sId, 4, 95))  // Batas Akhir
                batchRequests.put(createColumnWidthRequest(sId, 5, 95))  // Jam Selesai
                batchRequests.put(createColumnWidthRequest(sId, 6, 95))  // Status Ibadah
                batchRequests.put(createColumnWidthRequest(sId, 7, 140)) // Keterangan
            }

            // 6. Conditional Formatting Warna Lembut pada "Ringkasan" (Sudah: Hijau, Belum: Merah)
            if (!ringkasanHasConditionalFormatting && ringkasanSheetId != null) {
                // Rule: "Sudah" -> Hijau Lembut
                batchRequests.put(JSONObject().apply {
                    put("addConditionalFormatRule", JSONObject().apply {
                        put("rule", JSONObject().apply {
                            put("ranges", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("sheetId", ringkasanSheetId)
                                    put("startRowIndex", 1)
                                    put("endRowIndex", 1000)
                                    put("startColumnIndex", 1)
                                    put("endColumnIndex", 6)
                                })
                            })
                            put("booleanRule", JSONObject().apply {
                                put("condition", JSONObject().apply {
                                    put("type", "TEXT_EQ")
                                    put("values", JSONArray().apply {
                                        put(JSONObject().apply { put("userEnteredValue", "Sudah") })
                                    })
                                })
                                put("format", JSONObject().apply {
                                    put("backgroundColor", JSONObject().apply {
                                        put("red", 0.90f)
                                        put("green", 0.96f)
                                        put("blue", 0.92f)
                                    })
                                    put("textFormat", JSONObject().apply {
                                        put("foregroundColor", JSONObject().apply {
                                            put("red", 0.08f)
                                            put("green", 0.45f)
                                            put("blue", 0.20f)
                                        })
                                        put("bold", true)
                                    })
                                })
                            })
                        })
                        put("index", 0)
                    })
                })

                // Rule: "Belum" -> Merah Lembut
                batchRequests.put(JSONObject().apply {
                    put("addConditionalFormatRule", JSONObject().apply {
                        put("rule", JSONObject().apply {
                            put("ranges", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("sheetId", ringkasanSheetId)
                                    put("startRowIndex", 1)
                                    put("endRowIndex", 1000)
                                    put("startColumnIndex", 1)
                                    put("endColumnIndex", 6)
                                })
                            })
                            put("booleanRule", JSONObject().apply {
                                put("condition", JSONObject().apply {
                                    put("type", "TEXT_EQ")
                                    put("values", JSONArray().apply {
                                        put(JSONObject().apply { put("userEnteredValue", "Belum") })
                                    })
                                })
                                put("format", JSONObject().apply {
                                    put("backgroundColor", JSONObject().apply {
                                        put("red", 0.99f)
                                        put("green", 0.91f)
                                        put("blue", 0.91f)
                                    })
                                    put("textFormat", JSONObject().apply {
                                        put("foregroundColor", JSONObject().apply {
                                            put("red", 0.77f)
                                            put("green", 0.13f)
                                            put("blue", 0.12f)
                                        })
                                        put("bold", true)
                                    })
                                })
                            })
                        })
                        put("index", 1)
                    })
                })
            }

            if (batchRequests.length() > 0) {
                val batchUrl = "$SHEETS_API_BASE/$spreadsheetId:batchUpdate"
                val batchPayload = JSONObject().apply {
                    put("requests", batchRequests)
                }
                val batchReq = Request.Builder()
                    .url(batchUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .post(batchPayload.toString().toRequestBody(jsonMediaType))
                    .build()
                httpClient.newCall(batchReq).execute().use { bRes ->
                    if (!bRes.isSuccessful) {
                        val err = bRes.body?.string() ?: ""
                        android.util.Log.e("GoogleSheetsSync", "Batch formatting failed (${bRes.code}): $err")
                    }
                }
            }
            return sep
        } catch (e: Exception) {
            e.printStackTrace()
            return sep
        }
    }

    private fun createCenterAlignmentRequest(sheetId: Int, numCols: Int): JSONObject {
        return JSONObject().apply {
            put("repeatCell", JSONObject().apply {
                put("range", JSONObject().apply {
                    put("sheetId", sheetId)
                    put("startRowIndex", 1)
                    put("endRowIndex", 1000)
                    put("startColumnIndex", 0)
                    put("endColumnIndex", numCols)
                })
                put("cell", JSONObject().apply {
                    put("userEnteredFormat", JSONObject().apply {
                        put("horizontalAlignment", "CENTER")
                        put("verticalAlignment", "MIDDLE")
                    })
                })
                put("fields", "userEnteredFormat")
            })
        }
    }

    private fun createHeaderFormatRequest(sheetId: Int, numCols: Int): JSONObject {
        return JSONObject().apply {
            put("repeatCell", JSONObject().apply {
                put("range", JSONObject().apply {
                    put("sheetId", sheetId)
                    put("startRowIndex", 0)
                    put("endRowIndex", 1)
                    put("startColumnIndex", 0)
                    put("endColumnIndex", numCols)
                })
                put("cell", JSONObject().apply {
                    put("userEnteredFormat", JSONObject().apply {
                        put("backgroundColor", JSONObject().apply {
                            put("red", 0.11f)
                            put("green", 0.35f)
                            put("blue", 0.25f)
                        })
                        put("textFormat", JSONObject().apply {
                            put("foregroundColor", JSONObject().apply {
                                put("red", 1.0f)
                                put("green", 1.0f)
                                put("blue", 1.0f)
                            })
                            put("bold", true)
                            put("fontSize", 10)
                        })
                        put("horizontalAlignment", "CENTER")
                        put("verticalAlignment", "MIDDLE")
                    })
                })
                put("fields", "userEnteredFormat")
            })
        }
    }

    private fun createDateFormatRequest(sheetId: Int, colIndex: Int): JSONObject {
        return JSONObject().apply {
            put("repeatCell", JSONObject().apply {
                put("range", JSONObject().apply {
                    put("sheetId", sheetId)
                    put("startRowIndex", 1)
                    put("endRowIndex", 1000)
                    put("startColumnIndex", colIndex)
                    put("endColumnIndex", colIndex + 1)
                })
                put("cell", JSONObject().apply {
                    put("userEnteredFormat", JSONObject().apply {
                        put("numberFormat", JSONObject().apply {
                            put("type", "DATE")
                            put("pattern", "d MMMM yyyy")
                        })
                        put("horizontalAlignment", "CENTER")
                        put("verticalAlignment", "MIDDLE")
                    })
                })
                put("fields", "userEnteredFormat")
            })
        }
    }

    private fun createTextFormatRequest(sheetId: Int, colIndex: Int): JSONObject {
        return JSONObject().apply {
            put("repeatCell", JSONObject().apply {
                put("range", JSONObject().apply {
                    put("sheetId", sheetId)
                    put("startRowIndex", 1)
                    put("endRowIndex", 1000)
                    put("startColumnIndex", colIndex)
                    put("endColumnIndex", colIndex + 1)
                })
                put("cell", JSONObject().apply {
                    put("userEnteredFormat", JSONObject().apply {
                        put("numberFormat", JSONObject().apply {
                            put("type", "TEXT")
                        })
                        put("horizontalAlignment", "CENTER")
                        put("verticalAlignment", "MIDDLE")
                    })
                })
                put("fields", "userEnteredFormat")
            })
        }
    }

    private fun createColumnWidthRequest(sheetId: Int, colIndex: Int, pixelSize: Int): JSONObject {
        return JSONObject().apply {
            put("updateDimensionProperties", JSONObject().apply {
                put("range", JSONObject().apply {
                    put("sheetId", sheetId)
                    put("dimension", "COLUMNS")
                    put("startIndex", colIndex)
                    put("endIndex", colIndex + 1)
                })
                put("properties", JSONObject().apply {
                    put("pixelSize", pixelSize)
                })
                put("fields", "pixelSize")
            })
        }
    }

    private fun parseTimeToEpoch(dateStr: String, timeStr: String): Long {
        return try {
            val clean = timeStr.replace("WIB", "").replace("WITA", "").replace("WIT", "")
                .replace("(Qadha)", "").trim()
            val parts = clean.split(":")
            if (parts.size >= 2) {
                val h = parts[0].trim().toInt()
                val m = parts[1].trim().toInt()
                val pDate = LocalDate.parse(normalizeSheetDate(dateStr))
                pDate.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Menormalisasi tanggal dari Google Sheets ke format "yyyy-MM-dd" yang digunakan oleh database lokal.
     * Google Sheets bisa mengembalikan tanggal dalam beberapa format tergantung locale:
     * - "2026-09-11" (ISO, sudah benar)
     * - "11 September 2026" (format locale id_ID)
     * - "September 11, 2026" (format locale en_US)
     * - Angka serial Sheets (misalnya 46348)
     */
    private fun normalizeSheetDate(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""

        // Sudah format ISO yyyy-MM-dd?
        if (trimmed.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return trimmed

        // Coba parse "d MMMM yyyy" (locale Indonesia)
        try {
            val idFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("id", "ID"))
            val parsed = LocalDate.parse(trimmed, idFormatter)
            return parsed.toString() // yyyy-MM-dd
        } catch (_: Exception) { }

        // Coba parse "MMMM d, yyyy" (locale English)
        try {
            val enFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
            val parsed = LocalDate.parse(trimmed, enFormatter)
            return parsed.toString()
        } catch (_: Exception) { }

        // Coba parse "d/M/yyyy" atau "M/d/yyyy"
        try {
            val slashFormatter = DateTimeFormatter.ofPattern("d/M/yyyy")
            val parsed = LocalDate.parse(trimmed, slashFormatter)
            return parsed.toString()
        } catch (_: Exception) { }

        // Angka serial Google Sheets (hari sejak 30 Desember 1899)
        try {
            val serial = trimmed.toDouble().toLong()
            if (serial in 1..100000) {
                val baseDate = LocalDate.of(1899, 12, 30)
                return baseDate.plusDays(serial).toString()
            }
        } catch (_: Exception) { }

        return trimmed // Kembalikan apa adanya sebagai fallback
    }

    suspend fun trashSpreadsheet(): Result<Unit> = withContext(Dispatchers.IO) {
        val token = authManager.getAccessToken()
            ?: return@withContext Result.failure(IllegalStateException("Belum login akun Google"))
        try {
            val query = "name = '$SPREADSHEET_TITLE' and mimeType = 'application/vnd.google-apps.spreadsheet' and trashed = false"
            val url = "$DRIVE_FILES_BASE?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val searchRequest = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(searchRequest).execute().use { res ->
                if (res.isSuccessful) {
                    val json = JSONObject(res.body?.string() ?: "")
                    val files = json.optJSONArray("files")
                    if (files != null && files.length() > 0) {
                        for (i in 0 until files.length()) {
                            val fId = files.getJSONObject(i).getString("id")
                            val patchBody = JSONObject().put("trashed", true).toString().toRequestBody(jsonMediaType)
                            val patchReq = Request.Builder()
                                .url("$DRIVE_FILES_BASE/$fId")
                                .addHeader("Authorization", "Bearer $token")
                                .patch(patchBody)
                                .build()
                            httpClient.newCall(patchReq).execute().use { it.close() }
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
