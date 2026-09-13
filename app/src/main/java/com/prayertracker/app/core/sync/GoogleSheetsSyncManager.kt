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
import com.prayertracker.app.core.util.PrayerDateTimeUtils
import com.prayertracker.app.core.util.PrayerStatusResolver
import kotlinx.coroutines.flow.first

class GoogleSheetsSyncManager(
    private val database: AppDatabase,
    private val authManager: GoogleAuthManager,
    private val settingsRepository: AppSettingsRepository
) {
    private val httpClient = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val SPREADSHEET_TITLE = "Prayer Tracker - Catatan Ibadah"
    private val SHEETS_API_BASE = "https://sheets.googleapis.com/v4/spreadsheets"
    private val DRIVE_FILES_BASE = "https://www.googleapis.com/drive/v3/files"

    /** Format epoch ke HH:mm untuk kolom Jadwal Masuk & Batas Akhir */
    private fun formatEpoch(epoch: Long?): String = PrayerDateTimeUtils.formatEpoch(epoch)

    /** Format epoch ke yyyy-MM-dd HH:mm untuk kolom Jam Selesai — menghindari ambiguitas tengah malam */
    private fun formatEpochWithDate(epoch: Long?): String = PrayerDateTimeUtils.formatEpochWithDate(epoch)

    /** Parse nilai kolom Jam Selesai dari sheet menjadi epoch milli secara cerdas dan akurat */
    private fun parseJamSelesaiToEpoch(jamSelesaiStr: String, rDate: String, schedEpoch: Long): Long =
        PrayerDateTimeUtils.parseDateTimeToEpoch(jamSelesaiStr, rDate, schedEpoch)

    /** Menentukan keterangan status salat secara konsisten */
    fun resolveKeterangan(
        status: PrayerStatus,
        completedAtEpoch: Long?,
        scheduledTimeEpoch: Long,
        endTimeEpoch: Long
    ): String = PrayerStatusResolver.resolveKeterangan(status, completedAtEpoch, scheduledTimeEpoch, endTimeEpoch)

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
            // Pull tidak menulis ulang nilai user, tetapi tetap memperbarui rule warna
            // milik aplikasi agar tampilan sheet langsung konsisten.
            ensureSheetsAndFormatting(token, spreadsheetId)
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

                            val nowEpoch = System.currentTimeMillis()

                            // Cari entitas lokal baik dengan ID maupun pasangan (Nama Salat + Tanggal)
                            val localPrayer = (if (rowId.isNotBlank()) database.prayerRecordDao().getPrayerById(rowId) else null)
                                ?: database.prayerRecordDao().getPrayerByNameAndDate(pName, rDate)

                            val targetId = rowId.ifBlank { localPrayer?.id ?: java.util.UUID.randomUUID().toString() }
                            remoteIds.add(targetId)

                            val parsedSched = parseTimeToEpoch(rDate, rJadwal)
                            val parsedEnd = parseTimeToEpoch(rDate, rBatas)
                            val schedEpoch = if (parsedSched > 0) parsedSched else (localPrayer?.scheduledTimeEpoch ?: nowEpoch)
                            var endEpoch = if (parsedEnd > 0) parsedEnd else (localPrayer?.endTimeEpoch ?: (schedEpoch + 3600000))

                            // PENTING: Untuk salat Isya (atau salat apa pun yang batas akhirnya melewati tengah malam),
                            // endEpoch (misal 04:30 Subuh) akan lebih kecil atau sama dengan schedEpoch (misal 19:01 Isya).
                            // Tambahkan 24 jam (+1 hari / 86400000 ms) agar batas akhir akurat di pagi hari berikutnya,
                            // sehingga tidak dianggap kadaluarsa/terlewat (Qadha) saat dicek di pagi hari!
                            if (endEpoch > 0 && schedEpoch > 0 && endEpoch <= schedEpoch) {
                                endEpoch += 24 * 60 * 60 * 1000L
                            }

                            // Dinamis: Nilai Jam Selesai menjadi pemicu utama status ibadah
                            val parsedDone = parseJamSelesaiToEpoch(rJamSelesai, rDate, schedEpoch)
                            val hasDoneTime = parsedDone > 0L

                            val isRemoteDone = hasDoneTime ||
                                               rStatusIbadah.equals("Sudah", ignoreCase = true) ||
                                               rStatusIbadah.equals("Selesai", ignoreCase = true) ||
                                               rStatusIbadah.startsWith("Sudah", ignoreCase = true) ||
                                               rStatusIbadah.equals("Done", ignoreCase = true) ||
                                               rStatusIbadah.equals("Ya", ignoreCase = true) ||
                                               rStatusIbadah.contains("✓")

                            val isRemoteQadha = if (hasDoneTime) {
                                parsedDone > endEpoch
                            } else {
                                rKeterangan.contains("Qadha", ignoreCase = true) ||
                                rJamSelesai.contains("Qadha", ignoreCase = true) ||
                                rStatusIbadah.contains("Qadha", ignoreCase = true)
                            }

                            val targetStatus = if (isRemoteDone) {
                                if (isRemoteQadha) PrayerStatus.QADHA_COMPLETED else PrayerStatus.COMPLETED
                            } else {
                                if (nowEpoch > endEpoch) PrayerStatus.MISSED else PrayerStatus.PENDING
                            }

                            val targetCompletedAt: Long? = if (isRemoteDone) {
                                if (hasDoneTime) parsedDone else (localPrayer?.completedAtEpoch ?: schedEpoch)
                            } else {
                                null
                            }

                            if (localPrayer != null) {
                                val updatedPrayer = localPrayer.copy(
                                    scheduledTimeEpoch = schedEpoch,
                                    endTimeEpoch = endEpoch,
                                    status = targetStatus,
                                    completedAtEpoch = targetCompletedAt,
                                    syncStatus = SyncStatus.SYNCED,
                                    updatedAtEpoch = nowEpoch
                                )
                                database.prayerRecordDao().update(updatedPrayer)
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

        // 2. Baca tab "Ringkasan Harian" (atau "Ringkasan" lama) untuk menangkap editan langsung user pada tab pertama
        try {
            var ringkasanTitle = "Ringkasan Harian"
            var ringkasanRange = java.net.URLEncoder.encode("'$ringkasanTitle'!A2:F1000", "UTF-8")
            var ringkasanUrl = "$SHEETS_API_BASE/$spreadsheetId/values/$ringkasanRange?valueRenderOption=FORMULA"
            var ringkasanReq = Request.Builder()
                .url(ringkasanUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            var ringkasanRes = httpClient.newCall(ringkasanReq).execute()
            if (!ringkasanRes.isSuccessful && ringkasanRes.code == 400) {
                ringkasanRes.close()
                ringkasanTitle = "Ringkasan"
                ringkasanRange = java.net.URLEncoder.encode("'$ringkasanTitle'!A2:F1000", "UTF-8")
                ringkasanUrl = "$SHEETS_API_BASE/$spreadsheetId/values/$ringkasanRange?valueRenderOption=FORMULA"
                ringkasanReq = Request.Builder()
                    .url(ringkasanUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()
                ringkasanRes = httpClient.newCall(ringkasanReq).execute()
            }

            ringkasanRes.use { res ->
                if (res.isSuccessful) {
                    val rJson = JSONObject(res.body?.string() ?: "")
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
                                    val statusToSet = if (localPrayer.status == PrayerStatus.QADHA_COMPLETED) {
                                        PrayerStatus.QADHA_COMPLETED
                                    } else {
                                        PrayerStatus.COMPLETED
                                    }
                                    database.prayerRecordDao().updateStatus(
                                        id = localPrayer.id,
                                        status = statusToSet,
                                        completedAt = localPrayer.completedAtEpoch ?: nowEpoch,
                                        syncStatus = SyncStatus.SYNCED
                                    )
                                    if (statusToSet == PrayerStatus.COMPLETED) {
                                        database.qadhaRecordDao().deleteByPrayerRecordId(localPrayer.id)
                                    }
                                } else if (cellValue.equals("Belum", ignoreCase = true) || cellValue.equals("Terlewat", ignoreCase = true)) {
                                    var effEnd = localPrayer.endTimeEpoch
                                    if (effEnd > 0 && localPrayer.scheduledTimeEpoch > 0 && effEnd <= localPrayer.scheduledTimeEpoch) {
                                        effEnd += 24 * 60 * 60 * 1000L
                                    }
                                    val revertStatus = if (nowEpoch > effEnd) PrayerStatus.MISSED else PrayerStatus.PENDING
                                    val updated = localPrayer.copy(
                                        status = revertStatus,
                                        endTimeEpoch = effEnd,
                                        completedAtEpoch = null,
                                        syncStatus = SyncStatus.SYNCED,
                                        updatedAtEpoch = nowEpoch
                                    )
                                    database.prayerRecordDao().update(updated)
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

            // 2. Pastikan tab, layout, formula, dan warna tersedia.
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

            // Urutkan tanggal secara menurun (hari ini paling atas)
            // 3. Ambil seluruh data ibadah dari database lokal (termasuk hasil rekonsiliasi terbaru)
            val allPrayers = database.prayerRecordDao().getAllPrayers()
            val dates = allPrayers.map { it.prayerDate }.distinct().sortedDescending()
            val s = "$"

            // -----------------------------------------------------------------
            // Definitions for Weekly Summary Headers (Required by both empty and full sync)
            // -----------------------------------------------------------------
            val h1 = JSONArray().apply {
                put("Bulan")
                put("Minggu")
                put("Rentang Tanggal")
                val days = listOf("Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu")
                days.forEach { dayName ->
                    put(dayName)
                    put("")
                    put("")
                    put("")
                    put("")
                }
                put("Total Selesai")
            }
            val h2 = JSONArray().apply {
                put("")
                put("")
                put("")
                for (i in 0..6) {
                    put("S")
                    put("D")
                    put("A")
                    put("M")
                    put("I")
                }
                put("Target: 35")
            }

            // 3. Ambil seluruh data ibadah dari database lokal (termasuk hasil rekonsiliasi terbaru)
            if (allPrayers.isEmpty()) {
                // Inisialisasi header untuk keempat sheet agar spreadsheet baru tidak kosong melompong
                try {
                    val initPayload = JSONObject().apply {
                        put("valueInputOption", "USER_ENTERED")
                        put("data", JSONArray().apply {
                            put(JSONObject().apply {
                                put("range", "'Ringkasan Harian'!A1:G1")
                                put("majorDimension", "ROWS")
                                put("values", JSONArray().put(ringkasanHeader))
                            })
                            put(JSONObject().apply {
                                put("range", "'Ringkasan Mingguan'!A1:AM2")
                                put("majorDimension", "ROWS")
                                put("values", JSONArray().put(h1).put(h2))
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

            // -----------------------------------------------------------------
            // SHEET 1: "Ringkasan Harian" (Overview Dinamis Terhubung ke Data Mentah)
            // -----------------------------------------------------------------
            val ringkasanRows = JSONArray()
            ringkasanRows.put(ringkasanHeader)

            dates.forEachIndexed { idx, date ->
                val r = idx + 2 // Baris 1-indexed di Google Sheets (Header di baris 1, data mulai baris 2)
                val dateFormula = "=INDEX(UNIQUE(FILTER('Data Mentah'!${s}B:${s}B$sep 'Data Mentah'!${s}B:${s}B<>\"Tanggal\"$sep 'Data Mentah'!${s}B:${s}B<>\"\"))$sep ${idx + 1})"
                val row = JSONArray().apply {
                    put(dateFormula) // Kolom A (Tanggal dinamis terhubung langsung ke Data Mentah)
                    put("=IF(COUNTIFS('Data Mentah'!${s}B:${s}B$sep ${s}A$r$sep 'Data Mentah'!${s}C:${s}C$sep \"Subuh\"$sep 'Data Mentah'!${s}G:${s}G$sep \"Sudah*\")>0$sep \"Sudah\"$sep \"Belum\")")
                    put("=IF(COUNTIFS('Data Mentah'!${s}B:${s}B$sep ${s}A$r$sep 'Data Mentah'!${s}C:${s}C$sep \"Dzuhur\"$sep 'Data Mentah'!${s}G:${s}G$sep \"Sudah*\")>0$sep \"Sudah\"$sep \"Belum\")")
                    put("=IF(COUNTIFS('Data Mentah'!${s}B:${s}B$sep ${s}A$r$sep 'Data Mentah'!${s}C:${s}C$sep \"Ashar\"$sep 'Data Mentah'!${s}G:${s}G$sep \"Sudah*\")>0$sep \"Sudah\"$sep \"Belum\")")
                    put("=IF(COUNTIFS('Data Mentah'!${s}B:${s}B$sep ${s}A$r$sep 'Data Mentah'!${s}C:${s}C$sep \"Maghrib\"$sep 'Data Mentah'!${s}G:${s}G$sep \"Sudah*\")>0$sep \"Sudah\"$sep \"Belum\")")
                    put("=IF(COUNTIFS('Data Mentah'!${s}B:${s}B$sep ${s}A$r$sep 'Data Mentah'!${s}C:${s}C$sep \"Isya\"$sep 'Data Mentah'!${s}G:${s}G$sep \"Sudah*\")>0$sep \"Sudah\"$sep \"Belum\")")
                    put("=IF(${s}A$r=\"\"$sep \"\"$sep COUNTIF(B$r:F$r$sep \"Sudah*\") & \"/5 (\" & TEXT(COUNTIF(B$r:F$r$sep \"Sudah*\")/5$sep \"0%\") & \")\")")
                }
                ringkasanRows.put(row)
            }

            // -----------------------------------------------------------------
            // SHEET 2: "Ringkasan Mingguan" (Compact: Senin-Minggu x 5 Salat S-D-A-M-I)
            // -----------------------------------------------------------------
            val mingguanRows = JSONArray()
            mingguanRows.put(h1)
            mingguanRows.put(h2)

            val prayersOrder = listOf(
                PrayerName.FAJR,
                PrayerName.DHUHR,
                PrayerName.ASR,
                PrayerName.MAGHRIB,
                PrayerName.ISHA
            )
            // Hanya buat minggu yang benar-benar memiliki data. Sebelumnya ada
            // 104 x 39 formula setiap sync, termasuk untuk minggu kosong; beban
            // kalkulasi itulah yang paling sering menyebabkan sync timeout.
            val weeklyStarts = dates.mapNotNull { date ->
                runCatching { LocalDate.parse(date) }.getOrNull()
                    ?.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            }.distinct().sortedDescending()
            val indonesianLocale = java.util.Locale("id", "ID")
            val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", indonesianLocale)
            val rangeFormatter = DateTimeFormatter.ofPattern("d MMM", indonesianLocale)
            val today = LocalDate.now()

            weeklyStarts.forEachIndexed { idx, monday ->
                val r = idx + 3 // Baris 1 & 2 adalah header, data mulai baris 3.
                val row = JSONArray().apply {
                    put(monday.format(monthFormatter))
                    put("Minggu ${((monday.dayOfMonth - 1) / 7) + 1}")
                    put("${monday.format(rangeFormatter)} - ${monday.plusDays(6).format(rangeFormatter)}")

                    // Status tetap otomatis mengikuti Data Mentah, namun maksimal
                    // 35 formula per minggu yang memang punya data.
                    for (dayOffset in 0..6) {
                        val targetDate = monday.plusDays(dayOffset.toLong())
                        for (pName in prayersOrder) {
                            if (targetDate.isAfter(today)) {
                                put("")
                            } else {
                                val pDisplayName = pName.displayName
                                val targetDateText = targetDate.toString()
                                val targetDateFunction = "DATE(${targetDate.year}$sep ${targetDate.monthValue}$sep ${targetDate.dayOfMonth})"
                                put("=IF(OR(COUNTIFS('Data Mentah'!${s}B:${s}B$sep $targetDateFunction$sep 'Data Mentah'!${s}C:${s}C$sep \"$pDisplayName\"$sep 'Data Mentah'!${s}G:${s}G$sep \"Sudah*\")>0$sep COUNTIFS('Data Mentah'!${s}B:${s}B$sep \"$targetDateText\"$sep 'Data Mentah'!${s}C:${s}C$sep \"$pDisplayName\"$sep 'Data Mentah'!${s}G:${s}G$sep \"Sudah*\")>0)$sep \"✓\"$sep \"-\")")
                            }
                        }
                    }

                    put("=IF((COUNTIF(D$r:AL$r$sep \"✓\") + COUNTIF(D$r:AL$r$sep \"-\"))=0$sep \"-\"$sep COUNTIF(D$r:AL$r$sep \"✓\") & \"/\" & (COUNTIF(D$r:AL$r$sep \"✓\") + COUNTIF(D$r:AL$r$sep \"-\")) & \" (\" & TEXT(COUNTIF(D$r:AL$r$sep \"✓\") / (COUNTIF(D$r:AL$r$sep \"✓\") + COUNTIF(D$r:AL$r$sep \"-\"))$sep \"0%\") & \")\")")
                }
                mingguanRows.put(row)
            }

            // -----------------------------------------------------------------
            // SHEET 3: "Rekap Waktu" (Jam Selesai Dinamis Terhubung Langsung ke Data Mentah)
            // -----------------------------------------------------------------
            val rekapWaktuRows = JSONArray()
            rekapWaktuRows.put(rekapWaktuHeader)

            fun timeFormula(prayerName: String, r: Int): String {
                return "=IF(COUNTIFS('Data Mentah'!${s}B:${s}B$sep ${s}A$r$sep 'Data Mentah'!${s}C:${s}C$sep \"$prayerName\"$sep 'Data Mentah'!${s}G:${s}G$sep \"Sudah*\"$sep 'Data Mentah'!${s}F:${s}F$sep \"<>-\")>0$sep TEXT(INDEX(FILTER('Data Mentah'!${s}F:${s}F$sep 'Data Mentah'!${s}B:${s}B=${s}A$r$sep 'Data Mentah'!${s}C:${s}C=\"$prayerName\")$sep 1)$sep \"HH:mm\") & IF(COUNTIFS('Data Mentah'!${s}B:${s}B$sep ${s}A$r$sep 'Data Mentah'!${s}C:${s}C$sep \"$prayerName\"$sep 'Data Mentah'!${s}H:${s}H$sep \"*Qadha*\")>0$sep \" (Qadha)\"$sep \"\")$sep \"Belum\")"
            }

            dates.forEachIndexed { idx, date ->
                val r = idx + 2
                val dateFormula = "=INDEX(UNIQUE(FILTER('Data Mentah'!${s}B:${s}B$sep 'Data Mentah'!${s}B:${s}B<>\"Tanggal\"$sep 'Data Mentah'!${s}B:${s}B<>\"\"))$sep ${idx + 1})"
                val row = JSONArray().apply {
                    put(dateFormula) // Kolom A (Tanggal dinamis terhubung langsung ke Data Mentah)
                    put(timeFormula("Subuh", r))
                    put(timeFormula("Dzuhur", r))
                    put(timeFormula("Ashar", r))
                    put(timeFormula("Maghrib", r))
                    put(timeFormula("Isya", r))
                    put("=IF(${s}A$r=\"\"$sep \"\"$sep COUNTIF(B$r:F$r$sep \"<>Belum\") & \"/5 (\" & TEXT(COUNTIF(B$r:F$r$sep \"<>Belum\")/5$sep \"0%\") & \")\")")
                }
                rekapWaktuRows.put(row)
            }

            // -----------------------------------------------------------------
            // SHEET 4: "Data Mentah" (Rapi, Human-Friendly dengan ID Unik Teks)
            // -----------------------------------------------------------------
            val rawRows = JSONArray()
            rawRows.put(rawHeader)

            allPrayers.forEachIndexed { idx, p ->
                val r = idx + 2 // 1-indexed baris di Sheet Data Mentah (header di baris 1)
                val isDone = p.status == PrayerStatus.COMPLETED || p.status == PrayerStatus.QADHA_COMPLETED
                val jamSelesai = if (isDone) formatEpochWithDate(p.completedAtEpoch ?: p.scheduledTimeEpoch) else "-"
                val keterangan = resolveKeterangan(
                    status = p.status,
                    completedAtEpoch = p.completedAtEpoch,
                    scheduledTimeEpoch = p.scheduledTimeEpoch,
                    endTimeEpoch = p.endTimeEpoch
                )

                val statusFormula = PrayerStatusResolver.buildGoogleSheetsStatusFormula(r, sep, s)
                val keteranganFormula = PrayerStatusResolver.buildGoogleSheetsKeteranganFormula(r, sep, s)

                val row = JSONArray().apply {
                    put(p.id)
                    put(p.prayerDate)
                    put(p.prayerName.displayName)
                    put(formatEpoch(p.scheduledTimeEpoch))
                    put(formatEpoch(p.endTimeEpoch))
                    put(jamSelesai)
                    put(statusFormula)
                    put(keteranganFormula)
                }
                rawRows.put(row)
            }

            // 4. Batch Clear area sheet terlebih dahulu agar tidak ada data lama yang tersisa (dipangkas sesuai kolom aktif)
            try {
                val clearPayload = JSONObject().apply {
                    put("ranges", JSONArray().apply {
                        put("'Ringkasan Harian'!A:G")
                        put("'Ringkasan Mingguan'!A:AM")
                        put("'Rekap Waktu'!A:G")
                        put("'Data Mentah'!A:H")
                    })
                }
                val clearReq = Request.Builder()
                    .url("$SHEETS_API_BASE/$spreadsheetId/values:batchClear")
                    .addHeader("Authorization", "Bearer $token")
                    .post(clearPayload.toString().toRequestBody(jsonMediaType))
                    .build()
                httpClient.newCall(clearReq).execute().close()
            } catch (_: Exception) {
                // Abaikan jika sheet baru atau belum di-rename
            }

            // Nilai dua baris header mingguan harus ditulis ke sel terpisah dahulu;
            // merge-nya dipulihkan setelah batch values sukses.
            clearWeeklyHeaderMerges(token, spreadsheetId)

            // 5. Batch Update values keempat sheet sekaligus
            val updatePayload = JSONObject().apply {
                put("valueInputOption", "USER_ENTERED")
                put("data", JSONArray().apply {
                    put(JSONObject().apply {
                        put("range", "'Ringkasan Harian'!A1:G${ringkasanRows.length()}")
                        put("majorDimension", "ROWS")
                        put("values", ringkasanRows)
                    })
                    put(JSONObject().apply {
                        put("range", "'Ringkasan Mingguan'!A1:AM${mingguanRows.length()}")
                        put("majorDimension", "ROWS")
                        put("values", mingguanRows)
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

            // Header mingguan digabung setelah seluruh nilai header berhasil ditulis.
            // Dipisah dari conditional formatting agar layout tetap selalu diterapkan.
            applyWeeklyHeaderLayout(token, spreadsheetId)

            // 5. Pastikan semua sel data di semua sheet otomatis Rata Tengah (Horizontal & Vertikal) dan Diformat Estetik HANYA pada baris yang ada isinya
            applyCellStylingAndAlignments(
                token = token,
                spreadsheetId = spreadsheetId,
                ringkasanRowCount = ringkasanRows.length(),
                mingguanRowCount = mingguanRows.length(),
                rekapWaktuRowCount = rekapWaktuRows.length(),
                rawRowCount = rawRows.length()
            )

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

    private fun getSheetIdByTitle(token: String, spreadsheetId: String, title: String): Int? {
        val request = Request.Builder()
            .url("$SHEETS_API_BASE/$spreadsheetId?fields=sheets(properties(sheetId,title))")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val sheets = JSONObject(response.body?.string() ?: "{}").optJSONArray("sheets") ?: return@use null
            for (index in 0 until sheets.length()) {
                val properties = sheets.getJSONObject(index).getJSONObject("properties")
                if (properties.optString("title") == title) return@use properties.getInt("sheetId")
            }
            null
        }
    }

    private fun sendSpreadsheetBatch(
        token: String,
        spreadsheetId: String,
        requests: JSONArray,
        label: String
    ) {
        if (requests.length() == 0) return
        val request = Request.Builder()
            .url("$SHEETS_API_BASE/$spreadsheetId:batchUpdate")
            .addHeader("Authorization", "Bearer $token")
            .post(JSONObject().put("requests", requests).toString().toRequestBody(jsonMediaType))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string() ?: ""
                throw IllegalStateException("Gagal menerapkan $label (${response.code}): $detail")
            }
        }
    }

    /** Lepas merge sebelum nilai header dua baris ditulis ulang pada setiap sync. */
    private fun clearWeeklyHeaderMerges(token: String, spreadsheetId: String) {
        val sheetId = getSheetIdByTitle(token, spreadsheetId, "Ringkasan Mingguan") ?: return
        val requests = JSONArray().put(JSONObject().apply {
            put("unmergeCells", JSONObject().apply {
                put("range", JSONObject().apply {
                    put("sheetId", sheetId)
                    put("startRowIndex", 0)
                    put("endRowIndex", 2)
                    put("startColumnIndex", 0)
                    put("endColumnIndex", 39)
                })
            })
        })
        sendSpreadsheetBatch(token, spreadsheetId, requests, "reset header mingguan")
    }

    /** Pasang kembali pengelompokan header setelah values:batchUpdate selesai. */
    private fun applyWeeklyHeaderLayout(token: String, spreadsheetId: String) {
        val sheetId = getSheetIdByTitle(token, spreadsheetId, "Ringkasan Mingguan") ?: return
        val requests = JSONArray()
        // Format dulu ketika sel header masih terpisah; lalu merge kelompoknya.
        requests.put(createHeaderFormatRequest(sheetId, 39, numRows = 2))

        // Bulan, Minggu, dan Rentang Tanggal adalah label dua baris.
        listOf(0, 1, 2).forEach { column ->
            requests.put(JSONObject().apply {
                put("mergeCells", JSONObject().apply {
                    put("range", JSONObject().apply {
                        put("sheetId", sheetId)
                        put("startRowIndex", 0)
                        put("endRowIndex", 2)
                        put("startColumnIndex", column)
                        put("endColumnIndex", column + 1)
                    })
                    put("mergeType", "MERGE_ALL")
                })
            })
        }
        // Senin sampai Minggu: masing-masing menaungi lima salat S-D-A-M-I.
        for (dayIndex in 0..6) {
            val startColumn = 3 + dayIndex * 5
            requests.put(JSONObject().apply {
                put("mergeCells", JSONObject().apply {
                    put("range", JSONObject().apply {
                        put("sheetId", sheetId)
                        put("startRowIndex", 0)
                        put("endRowIndex", 1)
                        put("startColumnIndex", startColumn)
                        put("endColumnIndex", startColumn + 5)
                    })
                    put("mergeType", "MERGE_ALL")
                })
            })
        }
        // Total Selesai sengaja tidak di-merge vertikal supaya "Target: 35" di bawahnya tetap ada.
        requests.put(createColumnWidthRequest(sheetId, 0, 135))
        requests.put(createColumnWidthRequest(sheetId, 1, 90))
        requests.put(createColumnWidthRequest(sheetId, 2, 145))
        for (column in 3..37) requests.put(createColumnWidthRequest(sheetId, column, 24))
        requests.put(createColumnWidthRequest(sheetId, 38, 150))
        sendSpreadsheetBatch(token, spreadsheetId, requests, "merge dan ukuran header mingguan")
    }

    /**
     * Menjamin nama empat tab inti tersedia sebelum request values:batchUpdate dikirim.
     * Google Sheets menjalankan batchUpdate secara atomik: bila satu request format gagal,
     * rename/add sheet dalam batch yang sama ikut batal. Karena itu setup struktur dipisah
     * dari styling supaya range seperti 'Ringkasan Harian'!A1:G... selalu valid.
     */
    private fun ensureRequiredSheetTabs(token: String, spreadsheetId: String) {
        val getUrl = "$SHEETS_API_BASE/$spreadsheetId?fields=sheets(properties(sheetId,title))"
        val getReq = Request.Builder()
            .url(getUrl)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val sheets = httpClient.newCall(getReq).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string() ?: ""
                throw IllegalStateException("Gagal membaca struktur Google Sheet (${response.code}): $detail")
            }
            JSONObject(response.body?.string() ?: "{}").optJSONArray("sheets") ?: JSONArray()
        }
        if (sheets.length() == 0) {
            throw IllegalStateException("Google Sheet tidak memiliki tab yang dapat dipakai")
        }

        val titlesToIds = mutableMapOf<String, Int>()
        var firstSheetId: Int? = null
        for (i in 0 until sheets.length()) {
            val properties = sheets.getJSONObject(i).getJSONObject("properties")
            val sheetId = properties.getInt("sheetId")
            val title = properties.getString("title")
            titlesToIds[title] = sheetId
            if (i == 0) firstSheetId = sheetId
        }

        val requests = JSONArray()
        if (!titlesToIds.containsKey("Ringkasan Harian")) {
            val sourceId = titlesToIds["Ringkasan"] ?: firstSheetId
                ?: throw IllegalStateException("Tab utama Google Sheet tidak ditemukan")
            requests.put(JSONObject().apply {
                put("updateSheetProperties", JSONObject().apply {
                    put("properties", JSONObject().apply {
                        put("sheetId", sourceId)
                        put("title", "Ringkasan Harian")
                    })
                    put("fields", "title")
                })
            })
            titlesToIds.remove("Ringkasan")
            titlesToIds["Ringkasan Harian"] = sourceId
        }

        listOf(
            "Ringkasan Mingguan" to 1,
            "Rekap Waktu" to 2,
            "Data Mentah" to 3
        ).forEach { (title, index) ->
            if (!titlesToIds.containsKey(title)) {
                requests.put(JSONObject().apply {
                    put("addSheet", JSONObject().apply {
                        put("properties", JSONObject().apply {
                            put("title", title)
                            put("index", index)
                        })
                    })
                })
            }
        }

        if (requests.length() == 0) return

        val request = Request.Builder()
            .url("$SHEETS_API_BASE/$spreadsheetId:batchUpdate")
            .addHeader("Authorization", "Bearer $token")
            .post(JSONObject().put("requests", requests).toString().toRequestBody(jsonMediaType))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string() ?: ""
                throw IllegalStateException("Gagal menyiapkan tab Google Sheet (${response.code}): $detail")
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
            var ringkasanHarianSheetId: Int? = null
            var ringkasanMingguanSheetId: Int? = null
            var rekapWaktuSheetId: Int? = null
            var dataMentahSheetId: Int? = null
            var ringkasanHarianHasConditionalFormatting = false
            var ringkasanMingguanHasConditionalFormatting = false
            var rekapWaktuHasConditionalFormatting = false
            val conditionalFormatCounts = mutableMapOf<Int, Int>()
            val conditionalFormatsBySheet = mutableMapOf<Int, JSONArray>()
            // Dipakai hanya sebagai migrasi satu kali untuk rule semicolon dari rilis
            // sebelumnya. Sync normal tidak menghapus/menulis ulang semua rule.
            val conditionalFormatNeedsRepair = mutableSetOf<Int>()
            var ringkasanHarianHasTotalRules = false
            var ringkasanMingguanHasTotalRules = false
            var rekapWaktuHasTotalRules = false
            var dataMentahConditionalFormats: JSONArray? = null

            fun checkHasColumnRules(sheetObj: JSONObject, colIndex: Int): Boolean {
                val cFormats = sheetObj.optJSONArray("conditionalFormats") ?: return false
                for (idx in 0 until cFormats.length()) {
                    val rule = cFormats.optJSONObject(idx) ?: continue
                    val ranges = rule.optJSONArray("ranges") ?: rule.optJSONObject("booleanRule")?.optJSONArray("ranges")
                    if (ranges != null) {
                        for (j in 0 until ranges.length()) {
                            val r = ranges.optJSONObject(j) ?: continue
                            if (r.optInt("startColumnIndex") == colIndex) return true
                        }
                    }
                }
                return false
            }

            fun hasConditionalFormatRule(
                conditionalFormats: JSONArray?,
                colIndex: Int,
                conditionType: String,
                conditionValue: String
            ): Boolean {
                if (conditionalFormats == null) return false
                for (idx in 0 until conditionalFormats.length()) {
                    val rule = conditionalFormats.optJSONObject(idx) ?: continue
                    val ranges = rule.optJSONArray("ranges") ?: continue
                    val appliesToColumn = (0 until ranges.length()).any { rangeIndex ->
                        ranges.optJSONObject(rangeIndex)?.optInt("startColumnIndex", -1) == colIndex
                    }
                    val condition = rule.optJSONObject("booleanRule")?.optJSONObject("condition") ?: continue
                    val hasExpectedValue = (0 until (condition.optJSONArray("values")?.length() ?: 0)).any { valueIndex ->
                        condition.optJSONArray("values")
                            ?.optJSONObject(valueIndex)
                            ?.optString("userEnteredValue") == conditionValue
                    }
                    if (appliesToColumn && condition.optString("type") == conditionType && hasExpectedValue) {
                        return true
                    }
                }
                return false
            }

            for (i in 0 until sheetsArray.length()) {
                val sheetObj = sheetsArray.getJSONObject(i)
                val props = sheetObj.getJSONObject("properties")
                val title = props.getString("title")
                val sheetId = props.getInt("sheetId")
                existingTitles.add(title)
                val conditionalFormats = sheetObj.optJSONArray("conditionalFormats") ?: JSONArray()
                conditionalFormatsBySheet[sheetId] = conditionalFormats
                conditionalFormatCounts[sheetId] = conditionalFormats.length()
                if (conditionalFormats.toString().contains(";")) {
                    conditionalFormatNeedsRepair.add(sheetId)
                }
                if (i == 0) firstSheetId = sheetId
                if (title == "Ringkasan Harian" || title == "Ringkasan") {
                    ringkasanHarianSheetId = sheetId
                    val cFormats = sheetObj.optJSONArray("conditionalFormats")
                    if (cFormats != null && cFormats.length() > 0) {
                        ringkasanHarianHasConditionalFormatting = true
                    }
                    ringkasanHarianHasTotalRules = checkHasColumnRules(sheetObj, 6)
                }
                if (title == "Ringkasan Mingguan") {
                    ringkasanMingguanSheetId = sheetId
                    val cFormats = sheetObj.optJSONArray("conditionalFormats")
                    if (cFormats != null && cFormats.length() > 0) {
                        ringkasanMingguanHasConditionalFormatting = true
                    }
                    ringkasanMingguanHasTotalRules = checkHasColumnRules(sheetObj, 38)
                }
                if (title == "Rekap Waktu") {
                    rekapWaktuSheetId = sheetId
                    val cFormats = sheetObj.optJSONArray("conditionalFormats")
                    if (cFormats != null && cFormats.length() > 0) {
                        rekapWaktuHasConditionalFormatting = true
                    }
                    rekapWaktuHasTotalRules = checkHasColumnRules(sheetObj, 6)
                }
                if (title == "Data Mentah") {
                    dataMentahSheetId = sheetId
                    dataMentahConditionalFormats = sheetObj.optJSONArray("conditionalFormats")
                    // Data Mentah milik aplikasi hanya memakai lima rule warna.
                    // Bersihkan akumulasi rule duplikat dari versi lama sekali saja.
                    if ((dataMentahConditionalFormats?.length() ?: 0) > 5) {
                        conditionalFormatNeedsRepair.add(sheetId)
                    }
                }
            }

            // Summary tabs are fully owned by the app. A legacy/duplicate rule can
            // keep a cell green even when the current total is 2/5, so validate the
            // complete rule set once and rebuild it if it differs from the expected set.
            fun hasAllTotalRules(sheetId: Int, column: Int, formulas: List<String>): Boolean {
                val rules = conditionalFormatsBySheet[sheetId]
                return formulas.all { formula ->
                    hasConditionalFormatRule(rules, column, "CUSTOM_FORMULA", formula)
                }
            }

            ringkasanHarianSheetId?.let { sheetId ->
                ringkasanHarianHasTotalRules = hasAllTotalRules(sheetId, 6, listOf(
                    "=COUNTIF(B2:F2, \"Sudah*\")/5 >= 0.8",
                    "=COUNTIF(B2:F2, \"Sudah*\")/5 >= 0.5",
                    "=AND(A2<>\"\", COUNTIF(B2:F2, \"Sudah*\")/5 < 0.5)"
                ))
                if (!ringkasanHarianHasTotalRules || (conditionalFormatCounts[sheetId] ?: 0) != 5) {
                    conditionalFormatNeedsRepair.add(sheetId)
                }
            }
            ringkasanMingguanSheetId?.let { sheetId ->
                ringkasanMingguanHasTotalRules = hasAllTotalRules(sheetId, 38, listOf(
                    "=AND((COUNTIF(D3:AL3, \"✓\") + COUNTIF(D3:AL3, \"-\")) > 0, (COUNTIF(D3:AL3, \"✓\") / (COUNTIF(D3:AL3, \"✓\") + COUNTIF(D3:AL3, \"-\"))) >= 0.8)",
                    "=AND((COUNTIF(D3:AL3, \"✓\") + COUNTIF(D3:AL3, \"-\")) > 0, (COUNTIF(D3:AL3, \"✓\") / (COUNTIF(D3:AL3, \"✓\") + COUNTIF(D3:AL3, \"-\"))) >= 0.5)",
                    "=AND((COUNTIF(D3:AL3, \"✓\") + COUNTIF(D3:AL3, \"-\")) > 0, (COUNTIF(D3:AL3, \"✓\") / (COUNTIF(D3:AL3, \"✓\") + COUNTIF(D3:AL3, \"-\"))) < 0.5)"
                ))
                if (!ringkasanMingguanHasTotalRules || (conditionalFormatCounts[sheetId] ?: 0) != 5) {
                    conditionalFormatNeedsRepair.add(sheetId)
                }
            }
            rekapWaktuSheetId?.let { sheetId ->
                rekapWaktuHasTotalRules = hasAllTotalRules(sheetId, 6, listOf(
                    "=COUNTIF(B2:F2, \"<>Belum\")/5 >= 0.8",
                    "=COUNTIF(B2:F2, \"<>Belum\")/5 >= 0.5",
                    "=AND(A2<>\"\", COUNTIF(B2:F2, \"<>Belum\")/5 < 0.5)"
                ))
                if (!rekapWaktuHasTotalRules || (conditionalFormatCounts[sheetId] ?: 0) != 6) {
                    conditionalFormatNeedsRepair.add(sheetId)
                }
            }

            val batchRequests = JSONArray()

            fun submitFormattingBatch(requests: JSONArray, label: String): Boolean {
                if (requests.length() == 0) return true
                val request = Request.Builder()
                    .url("$SHEETS_API_BASE/$spreadsheetId:batchUpdate")
                    .addHeader("Authorization", "Bearer $token")
                    .post(JSONObject().put("requests", requests).toString().toRequestBody(jsonMediaType))
                    .build()
                return httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val detail = response.body?.string() ?: ""
                        android.util.Log.e("GoogleSheetsSync", "Gagal menerapkan $label (${response.code}): $detail")
                    }
                    response.isSuccessful
                }
            }

            // API Sheets mewajibkan index saat menambah conditional format.
            // Dengan counter per-sheet, rule baru selalu ditambahkan di bawah rule yang sudah ada.
            fun nextConditionalFormatIndex(sheetId: Int): Int {
                val index = conditionalFormatCounts[sheetId] ?: 0
                conditionalFormatCounts[sheetId] = index + 1
                return index
            }

            // Formula conditional-format API memakai sintaks koma, tidak mengikuti
            // pemisah rumus USER_ENTERED pada locale spreadsheet.
            fun apiConditionalFormula(formula: String): String = formula

            fun repairConditionalRulesOnce(sheetId: Int) {
                repeat(conditionalFormatCounts[sheetId] ?: 0) {
                    batchRequests.put(JSONObject().apply {
                        put("deleteConditionalFormatRule", JSONObject().apply {
                            put("sheetId", sheetId)
                            put("index", 0)
                        })
                    })
                }
                conditionalFormatCounts[sheetId] = 0
            }

            // 1. Rename sheet pertama jika masih bernama "Ringkasan" -> "Ringkasan Harian"
            if (existingTitles.contains("Ringkasan") && !existingTitles.contains("Ringkasan Harian")) {
                val rId = ringkasanHarianSheetId ?: firstSheetId
                if (rId != null) {
                    batchRequests.put(JSONObject().apply {
                        put("updateSheetProperties", JSONObject().apply {
                            put("properties", JSONObject().apply {
                                put("sheetId", rId)
                                put("title", "Ringkasan Harian")
                            })
                            put("fields", "title")
                        })
                    })
                    ringkasanHarianSheetId = rId
                }
            } else if (!existingTitles.contains("Ringkasan Harian") && firstSheetId != null) {
                batchRequests.put(JSONObject().apply {
                    put("updateSheetProperties", JSONObject().apply {
                        put("properties", JSONObject().apply {
                            put("sheetId", firstSheetId)
                            put("title", "Ringkasan Harian")
                        })
                        put("fields", "title")
                    })
                })
                ringkasanHarianSheetId = firstSheetId
            }

            // 2. Tambah "Ringkasan Mingguan" jika belum ada
            if (!existingTitles.contains("Ringkasan Mingguan")) {
                ringkasanMingguanSheetId = 104
                batchRequests.put(JSONObject().apply {
                    put("addSheet", JSONObject().apply {
                        put("properties", JSONObject().apply {
                            put("sheetId", ringkasanMingguanSheetId)
                            put("title", "Ringkasan Mingguan")
                            put("index", 1)
                        })
                    })
                })
            }

            // 3. Tambah "Rekap Waktu" jika belum ada
            if (!existingTitles.contains("Rekap Waktu")) {
                rekapWaktuSheetId = 101
                batchRequests.put(JSONObject().apply {
                    put("addSheet", JSONObject().apply {
                        put("properties", JSONObject().apply {
                            put("sheetId", rekapWaktuSheetId)
                            put("title", "Rekap Waktu")
                            put("index", 2)
                        })
                    })
                })
            }

            // 4. Tambah "Data Mentah" jika belum ada
            if (!existingTitles.contains("Data Mentah")) {
                dataMentahSheetId = 102
                batchRequests.put(JSONObject().apply {
                    put("addSheet", JSONObject().apply {
                        put("properties", JSONObject().apply {
                            put("sheetId", dataMentahSheetId)
                            put("title", "Data Mentah")
                            put("index", 3)
                        })
                    })
                })
            }

            // 5. Hapus Garis Kisi (hideGridlines: true), Bekukan Baris Header,
            // dan PANGKAS KOLOM BERLEBIH agar sheet bersih dan rapi!
            ringkasanHarianSheetId?.let { sId ->
                batchRequests.put(JSONObject().apply {
                    put("updateSheetProperties", JSONObject().apply {
                        put("properties", JSONObject().apply {
                            put("sheetId", sId)
                            put("gridProperties", JSONObject().apply {
                                put("frozenRowCount", 1)
                                put("hideGridlines", true)
                                put("columnCount", 7) // Hanya Kolom A - G
                            })
                        })
                        put("fields", "gridProperties.frozenRowCount,gridProperties.hideGridlines,gridProperties.columnCount")
                    })
                })
            }

            ringkasanMingguanSheetId?.let { sId ->
                batchRequests.put(JSONObject().apply {
                    put("updateSheetProperties", JSONObject().apply {
                        put("properties", JSONObject().apply {
                            put("sheetId", sId)
                            put("gridProperties", JSONObject().apply {
                                put("frozenRowCount", 2) // Bekukan 2 baris header
                                put("hideGridlines", true)
                                put("columnCount", 39) // Kolom A s.d. AM
                            })
                        })
                        put("fields", "gridProperties.frozenRowCount,gridProperties.hideGridlines,gridProperties.columnCount")
                    })
                })
            }

            rekapWaktuSheetId?.let { sId ->
                batchRequests.put(JSONObject().apply {
                    put("updateSheetProperties", JSONObject().apply {
                        put("properties", JSONObject().apply {
                            put("sheetId", sId)
                            put("gridProperties", JSONObject().apply {
                                put("frozenRowCount", 1)
                                put("hideGridlines", true)
                                put("columnCount", 7) // Hanya Kolom A - G
                            })
                        })
                        put("fields", "gridProperties.frozenRowCount,gridProperties.hideGridlines,gridProperties.columnCount")
                    })
                })
            }

            dataMentahSheetId?.let { sId ->
                batchRequests.put(JSONObject().apply {
                    put("updateSheetProperties", JSONObject().apply {
                        put("properties", JSONObject().apply {
                            put("sheetId", sId)
                            put("gridProperties", JSONObject().apply {
                                put("frozenRowCount", 1)
                                put("hideGridlines", true)
                                put("columnCount", 8) // Hanya Kolom A - H
                            })
                        })
                        put("fields", "gridProperties.frozenRowCount,gridProperties.hideGridlines,gridProperties.columnCount")
                    })
                })
            }

            // 6. Header Styling: Background Hijau Emerald Elegan (#1B4D3E), Teks Putih Tebal (Bold), Rata Tengah
            // Ringkasan Harian: 7 kolom (0..7)
            ringkasanHarianSheetId?.let { sId ->
                batchRequests.put(createHeaderFormatRequest(sId, 7))
                batchRequests.put(createCenterAlignmentRequest(sId, 7))
                batchRequests.put(createDateFormatRequest(sId, 0)) // Kolom Tanggal (indeks 0) format "d MMMM yyyy"
                batchRequests.put(createColumnWidthRequest(sId, 0, 140)) // Tanggal lebih lebar
                for (col in 1..5) {
                    batchRequests.put(createColumnWidthRequest(sId, col, 95))
                }
                batchRequests.put(createColumnWidthRequest(sId, 6, 145)) // Muat "5/5 (100%)"
            }

            // Ringkasan Mingguan: 39 kolom (0..39), 2 baris header + merge cells
            ringkasanMingguanSheetId?.let { sId ->
                batchRequests.put(createHeaderFormatRequest(sId, 39, numRows = 2))
                batchRequests.put(createCenterAlignmentRequest(sId, 39, startRowIndex = 2))

                // Blok ceklis dibuat padat; satu simbol ✓/- cukup 26px. Kolom
                // informasi minggu tetap lega agar teks tidak bertumpuk.
                batchRequests.put(createColumnWidthRequest(sId, 0, 135)) // Bulan
                batchRequests.put(createColumnWidthRequest(sId, 1, 90))  // Minggu
                batchRequests.put(createColumnWidthRequest(sId, 2, 145)) // Rentang Tanggal
                for (col in 3..37) {
                    batchRequests.put(createColumnWidthRequest(sId, col, 24)) // S, D, A, M, I
                }
                batchRequests.put(createColumnWidthRequest(sId, 38, 150)) // Muat "35/35 (100%)"
            }

            // Rekap Waktu: 7 kolom (0..7)
            rekapWaktuSheetId?.let { sId ->
                batchRequests.put(createHeaderFormatRequest(sId, 7))
                batchRequests.put(createCenterAlignmentRequest(sId, 7))
                batchRequests.put(createDateFormatRequest(sId, 0))
                batchRequests.put(createColumnWidthRequest(sId, 0, 140))
                for (col in 1..5) {
                    batchRequests.put(createColumnWidthRequest(sId, col, 120)) // Kolom jam + keterangan Qadha diperlebar
                    batchRequests.put(createTextFormatRequest(sId, col))
                }
                batchRequests.put(createColumnWidthRequest(sId, 6, 145)) // Muat "5/5 (100%)"
            }

            // Data Mentah: 8 kolom (0..8)
            dataMentahSheetId?.let { sId ->
                batchRequests.put(createHeaderFormatRequest(sId, 8))
                batchRequests.put(createCenterAlignmentRequest(sId, 8))
                batchRequests.put(createTextFormatRequest(sId, 0)) // Kolom ID di indeks 0 adalah teks murni, bukan tanggal!
                batchRequests.put(createDateFormatRequest(sId, 1)) // Kolom Tanggal di indeks 1
                // Lebar proporsional: UUID dan tanggal-jam harus utuh, sedangkan data
                // pendek tetap ringkas agar tab nyaman dibaca tanpa banyak scroll.
                batchRequests.put(createColumnWidthRequest(sId, 0, 430))  // ID / UUID 36 karakter
                batchRequests.put(createColumnWidthRequest(sId, 1, 135))  // Tanggal: yyyy-MM-dd
                batchRequests.put(createColumnWidthRequest(sId, 2, 100))  // Salat
                batchRequests.put(createColumnWidthRequest(sId, 3, 120))  // Jadwal Masuk
                batchRequests.put(createColumnWidthRequest(sId, 4, 120))  // Batas Akhir
                batchRequests.put(createColumnWidthRequest(sId, 5, 185))  // Jam Selesai: yyyy-MM-dd HH:mm
                batchRequests.put(createColumnWidthRequest(sId, 6, 120))  // Status Ibadah
                batchRequests.put(createColumnWidthRequest(sId, 7, 260))  // Keterangan
            }

            // Kirim struktur terlebih dahulu. Formula conditional-format yang bermasalah
            // tidak boleh membatalkan merge header atau perubahan lebar kolom.
            submitFormattingBatch(batchRequests, "layout Google Sheet")
            while (batchRequests.length() > 0) batchRequests.remove(0)

            // 7. Conditional Formatting Warna Lembut pada "Ringkasan Harian" (Sudah: Hijau, Belum: Merah, Total: Skala Persen)
            listOfNotNull(ringkasanHarianSheetId, ringkasanMingguanSheetId, rekapWaktuSheetId, dataMentahSheetId)
                .filter { it in conditionalFormatNeedsRepair }
                .forEach(::repairConditionalRulesOnce)

            if ((!ringkasanHarianHasConditionalFormatting || ringkasanHarianSheetId?.let(conditionalFormatNeedsRepair::contains) == true) && ringkasanHarianSheetId != null) {
                // Rule: "Sudah" -> Hijau Lembut
                batchRequests.put(createConditionalFormatRule(
                    sheetId = ringkasanHarianSheetId,
                    startRow = 1, endRow = 1000, startCol = 1, endCol = 6,
                    conditionType = "TEXT_EQ", conditionValue = "Sudah",
                    bgRed = 0.90f, bgGreen = 0.96f, bgBlue = 0.92f,
                    textRed = 0.08f, textGreen = 0.45f, textBlue = 0.20f,
                    index = 0
                ))
                // Rule: "Belum" -> Merah Lembut
                batchRequests.put(createConditionalFormatRule(
                    sheetId = ringkasanHarianSheetId,
                    startRow = 1, endRow = 1000, startCol = 1, endCol = 6,
                    conditionType = "TEXT_EQ", conditionValue = "Belum",
                    bgRed = 0.99f, bgGreen = 0.91f, bgBlue = 0.91f,
                    textRed = 0.77f, textGreen = 0.13f, textBlue = 0.12f,
                    index = 1
                ))
            }
            if ((!ringkasanHarianHasTotalRules || ringkasanHarianSheetId?.let(conditionalFormatNeedsRepair::contains) == true) && ringkasanHarianSheetId != null) {
                // Kolom 6 (Total Selesai): >= 80% Hijau Lembut
                batchRequests.put(createConditionalFormatRule(
                    sheetId = ringkasanHarianSheetId,
                    startRow = 1, endRow = 1000, startCol = 6, endCol = 7,
                    conditionType = "CUSTOM_FORMULA", conditionValue = apiConditionalFormula("=COUNTIF(B2:F2, \"Sudah*\")/5 >= 0.8"),
                    bgRed = 0.92f, bgGreen = 0.96f, bgBlue = 0.93f,
                    textRed = 0.08f, textGreen = 0.45f, textBlue = 0.20f,
                    index = nextConditionalFormatIndex(ringkasanHarianSheetId)
                ))
                // Kolom 6 (Total Selesai): 50% - 79% Kuning/Oranye Lembut
                batchRequests.put(createConditionalFormatRule(
                    sheetId = ringkasanHarianSheetId,
                    startRow = 1, endRow = 1000, startCol = 6, endCol = 7,
                    conditionType = "CUSTOM_FORMULA", conditionValue = apiConditionalFormula("=COUNTIF(B2:F2, \"Sudah*\")/5 >= 0.5"),
                    bgRed = 1.0f, bgGreen = 0.98f, bgBlue = 0.90f,
                    textRed = 0.80f, textGreen = 0.40f, textBlue = 0.00f,
                    index = nextConditionalFormatIndex(ringkasanHarianSheetId)
                ))
                // Kolom 6 (Total Selesai): < 50% Merah Lembut
                batchRequests.put(createConditionalFormatRule(
                    sheetId = ringkasanHarianSheetId,
                    startRow = 1, endRow = 1000, startCol = 6, endCol = 7,
                    conditionType = "CUSTOM_FORMULA", conditionValue = apiConditionalFormula("=AND(A2<>\"\", COUNTIF(B2:F2, \"Sudah*\")/5 < 0.5)"),
                    bgRed = 0.99f, bgGreen = 0.93f, bgBlue = 0.93f,
                    textRed = 0.77f, textGreen = 0.13f, textBlue = 0.12f,
                    index = nextConditionalFormatIndex(ringkasanHarianSheetId)
                ))
            }

            // 8. Conditional Formatting Warna Lembut pada "Ringkasan Mingguan" (✓: Hijau, -: Merah, Total: Skala Persen)
            if (ringkasanMingguanSheetId != null &&
                (ringkasanMingguanSheetId?.let(conditionalFormatNeedsRepair::contains) == true ||
                    !hasConditionalFormatRule(conditionalFormatsBySheet[ringkasanMingguanSheetId], 3, "TEXT_EQ", "✓"))) {
                // Rule: "✓" -> Hijau Lembut
                batchRequests.put(createConditionalFormatRule(
                    sheetId = ringkasanMingguanSheetId,
                    startRow = 2, endRow = 1000, startCol = 3, endCol = 38,
                    conditionType = "TEXT_EQ", conditionValue = "✓",
                    bgRed = 0.90f, bgGreen = 0.96f, bgBlue = 0.92f,
                    textRed = 0.08f, textGreen = 0.45f, textBlue = 0.20f,
                    index = nextConditionalFormatIndex(ringkasanMingguanSheetId)
                ))
            }
            if (ringkasanMingguanSheetId != null &&
                (ringkasanMingguanSheetId?.let(conditionalFormatNeedsRepair::contains) == true ||
                    !hasConditionalFormatRule(conditionalFormatsBySheet[ringkasanMingguanSheetId], 3, "TEXT_EQ", "-"))) {
                // Rule: "-" -> Merah Lembut
                batchRequests.put(createConditionalFormatRule(
                    sheetId = ringkasanMingguanSheetId,
                    startRow = 2, endRow = 1000, startCol = 3, endCol = 38,
                    conditionType = "TEXT_EQ", conditionValue = "-",
                    bgRed = 0.99f, bgGreen = 0.91f, bgBlue = 0.91f,
                    textRed = 0.77f, textGreen = 0.13f, textBlue = 0.12f,
                    index = nextConditionalFormatIndex(ringkasanMingguanSheetId)
                ))
            }
            if ((!ringkasanMingguanHasTotalRules || ringkasanMingguanSheetId?.let(conditionalFormatNeedsRepair::contains) == true) && ringkasanMingguanSheetId != null) {
                // Kolom 38 (Total Selesai): >= 80% Hijau Lembut
                batchRequests.put(createConditionalFormatRule(
                    sheetId = ringkasanMingguanSheetId,
                    startRow = 2, endRow = 1000, startCol = 38, endCol = 39,
                    conditionType = "CUSTOM_FORMULA", conditionValue = apiConditionalFormula("=AND((COUNTIF(D3:AL3, \"✓\") + COUNTIF(D3:AL3, \"-\")) > 0, (COUNTIF(D3:AL3, \"✓\") / (COUNTIF(D3:AL3, \"✓\") + COUNTIF(D3:AL3, \"-\"))) >= 0.8)"),
                    bgRed = 0.92f, bgGreen = 0.96f, bgBlue = 0.93f,
                    textRed = 0.08f, textGreen = 0.45f, textBlue = 0.20f,
                    index = nextConditionalFormatIndex(ringkasanMingguanSheetId)
                ))
                // Kolom 38 (Total Selesai): 50% - 79% Kuning/Oranye Lembut
                batchRequests.put(createConditionalFormatRule(
                    sheetId = ringkasanMingguanSheetId,
                    startRow = 2, endRow = 1000, startCol = 38, endCol = 39,
                    conditionType = "CUSTOM_FORMULA", conditionValue = apiConditionalFormula("=AND((COUNTIF(D3:AL3, \"✓\") + COUNTIF(D3:AL3, \"-\")) > 0, (COUNTIF(D3:AL3, \"✓\") / (COUNTIF(D3:AL3, \"✓\") + COUNTIF(D3:AL3, \"-\"))) >= 0.5)"),
                    bgRed = 1.0f, bgGreen = 0.98f, bgBlue = 0.90f,
                    textRed = 0.80f, textGreen = 0.40f, textBlue = 0.00f,
                    index = nextConditionalFormatIndex(ringkasanMingguanSheetId)
                ))
                // Kolom 38 (Total Selesai): < 50% Merah Lembut
                batchRequests.put(createConditionalFormatRule(
                    sheetId = ringkasanMingguanSheetId,
                    startRow = 2, endRow = 1000, startCol = 38, endCol = 39,
                    conditionType = "CUSTOM_FORMULA", conditionValue = apiConditionalFormula("=AND((COUNTIF(D3:AL3, \"✓\") + COUNTIF(D3:AL3, \"-\")) > 0, (COUNTIF(D3:AL3, \"✓\") / (COUNTIF(D3:AL3, \"✓\") + COUNTIF(D3:AL3, \"-\"))) < 0.5)"),
                    bgRed = 0.99f, bgGreen = 0.93f, bgBlue = 0.93f,
                    textRed = 0.77f, textGreen = 0.13f, textBlue = 0.12f,
                    index = nextConditionalFormatIndex(ringkasanMingguanSheetId)
                ))
            }

            // 9. Conditional Formatting Warna Lembut pada "Rekap Waktu" (Qadha: Oranye, Tepat Waktu: Hijau, Belum: Merah, Total: Skala Persen)
            if ((!rekapWaktuHasConditionalFormatting || rekapWaktuSheetId?.let(conditionalFormatNeedsRepair::contains) == true) && rekapWaktuSheetId != null) {
                // Rule 1: Ada teks "(Qadha)" -> Oranye Lembut
                batchRequests.put(createConditionalFormatRule(
                    sheetId = rekapWaktuSheetId,
                    startRow = 1, endRow = 1000, startCol = 1, endCol = 6,
                    conditionType = "TEXT_CONTAINS", conditionValue = "Qadha",
                    bgRed = 1.0f, bgGreen = 0.95f, bgBlue = 0.82f,
                    textRed = 0.80f, textGreen = 0.40f, textBlue = 0.05f,
                    index = 0
                ))
                // Rule 2: Ada jam (berisi tanda ":") -> Hijau Lembut
                batchRequests.put(createConditionalFormatRule(
                    sheetId = rekapWaktuSheetId,
                    startRow = 1, endRow = 1000, startCol = 1, endCol = 6,
                    conditionType = "TEXT_CONTAINS", conditionValue = ":",
                    bgRed = 0.90f, bgGreen = 0.96f, bgBlue = 0.92f,
                    textRed = 0.08f, textGreen = 0.45f, textBlue = 0.20f,
                    index = 1
                ))
                // Rule 3: "Belum" -> Merah Lembut
                batchRequests.put(createConditionalFormatRule(
                    sheetId = rekapWaktuSheetId,
                    startRow = 1, endRow = 1000, startCol = 1, endCol = 6,
                    conditionType = "TEXT_EQ", conditionValue = "Belum",
                    bgRed = 0.99f, bgGreen = 0.91f, bgBlue = 0.91f,
                    textRed = 0.77f, textGreen = 0.13f, textBlue = 0.12f,
                    index = 2
                ))
            }
            if ((!rekapWaktuHasTotalRules || rekapWaktuSheetId?.let(conditionalFormatNeedsRepair::contains) == true) && rekapWaktuSheetId != null) {
                // Kolom 6 (Total Selesai): >= 80% Hijau Lembut
                batchRequests.put(createConditionalFormatRule(
                    sheetId = rekapWaktuSheetId,
                    startRow = 1, endRow = 1000, startCol = 6, endCol = 7,
                    conditionType = "CUSTOM_FORMULA", conditionValue = apiConditionalFormula("=COUNTIF(B2:F2, \"<>Belum\")/5 >= 0.8"),
                    bgRed = 0.92f, bgGreen = 0.96f, bgBlue = 0.93f,
                    textRed = 0.08f, textGreen = 0.45f, textBlue = 0.20f,
                    index = nextConditionalFormatIndex(rekapWaktuSheetId)
                ))
                // Kolom 6 (Total Selesai): 50% - 79% Kuning/Oranye Lembut
                batchRequests.put(createConditionalFormatRule(
                    sheetId = rekapWaktuSheetId,
                    startRow = 1, endRow = 1000, startCol = 6, endCol = 7,
                    conditionType = "CUSTOM_FORMULA", conditionValue = apiConditionalFormula("=COUNTIF(B2:F2, \"<>Belum\")/5 >= 0.5"),
                    bgRed = 1.0f, bgGreen = 0.98f, bgBlue = 0.90f,
                    textRed = 0.80f, textGreen = 0.40f, textBlue = 0.00f,
                    index = nextConditionalFormatIndex(rekapWaktuSheetId)
                ))
                // Kolom 6 (Total Selesai): < 50% Merah Lembut
                batchRequests.put(createConditionalFormatRule(
                    sheetId = rekapWaktuSheetId,
                    startRow = 1, endRow = 1000, startCol = 6, endCol = 7,
                    conditionType = "CUSTOM_FORMULA", conditionValue = apiConditionalFormula("=AND(A2<>\"\", COUNTIF(B2:F2, \"<>Belum\")/5 < 0.5)"),
                    bgRed = 0.99f, bgGreen = 0.93f, bgBlue = 0.93f,
                    textRed = 0.77f, textGreen = 0.13f, textBlue = 0.12f,
                    index = nextConditionalFormatIndex(rekapWaktuSheetId)
                ))
            }

            // 10. Conditional Formatting Warna Lembut pada "Data Mentah".
            // Jangan ditambah ulang setiap sync: rule duplikat akan terus menumpuk
            // dan memperlambat spreadsheet sampai proses sync bisa timeout.
            dataMentahSheetId?.let { sheetId ->
                val rawRules = dataMentahConditionalFormats
                val rawRulesNeedSetup = sheetId in conditionalFormatNeedsRepair ||
                    !hasConditionalFormatRule(rawRules, 6, "TEXT_EQ", "Sudah") ||
                    !hasConditionalFormatRule(rawRules, 6, "TEXT_EQ", "Belum") ||
                    !hasConditionalFormatRule(rawRules, 7, "CUSTOM_FORMULA", "=OR(H2=\"Tepat Waktu\", H2=\"Tepat Waktu (Awal Waktu)\")") ||
                    !hasConditionalFormatRule(rawRules, 7, "CUSTOM_FORMULA", "=OR(H2=\"Akhir Waktu\", H2=\"Sebelum Waktu Masuk\", H2=\"Qadha Selesai\")") ||
                    !hasConditionalFormatRule(rawRules, 7, "CUSTOM_FORMULA", "=OR(H2=\"Belum Salat\", H2=\"Terlewat (Belum Qadha)\")")

                if (!rawRulesNeedSetup) return@let

                fun addDataMentahRule(
                    colIndex: Int,
                    conditionType: String,
                    conditionValue: String,
                    bgRed: Float, bgGreen: Float, bgBlue: Float,
                    textRed: Float, textGreen: Float, textBlue: Float
                ) {
                    batchRequests.put(createConditionalFormatRule(
                        sheetId = sheetId,
                        startRow = 1, endRow = 1000, startCol = colIndex, endCol = colIndex + 1,
                        conditionType = conditionType,
                        conditionValue = if (conditionType == "CUSTOM_FORMULA") apiConditionalFormula(conditionValue) else conditionValue,
                        bgRed = bgRed, bgGreen = bgGreen, bgBlue = bgBlue,
                        textRed = textRed, textGreen = textGreen, textBlue = textBlue,
                        index = nextConditionalFormatIndex(sheetId)
                    ))
                }

                // Status Ibadah (Col 6): "Sudah" hijau, "Belum" merah.
                addDataMentahRule(6, "TEXT_EQ", "Sudah", 0.90f, 0.96f, 0.92f, 0.08f, 0.45f, 0.20f)
                addDataMentahRule(6, "TEXT_EQ", "Belum", 0.99f, 0.91f, 0.91f, 0.77f, 0.13f, 0.12f)

                // Keterangan (Col 7): Menggunakan CUSTOM_FORMULA agar 100% konsisten & anti-bug text matching di Google Sheets
                // Rule 2: Hijau Lembut untuk Tepat Waktu & Awal Waktu
                addDataMentahRule(7, "CUSTOM_FORMULA", "=OR(H2=\"Tepat Waktu\", H2=\"Tepat Waktu (Awal Waktu)\")", 0.90f, 0.96f, 0.92f, 0.08f, 0.45f, 0.20f)

                // Rule 3: Oranye Lembut untuk Akhir Waktu, Sebelum Waktu Masuk, & Qadha Selesai
                addDataMentahRule(7, "CUSTOM_FORMULA", "=OR(H2=\"Akhir Waktu\", H2=\"Sebelum Waktu Masuk\", H2=\"Qadha Selesai\")", 1.0f, 0.95f, 0.82f, 0.80f, 0.40f, 0.05f)

                // Rule 4: Merah Lembut untuk Belum Salat & Terlewat (Belum Qadha)
                addDataMentahRule(7, "CUSTOM_FORMULA", "=OR(H2=\"Belum Salat\", H2=\"Terlewat (Belum Qadha)\")", 0.99f, 0.91f, 0.91f, 0.77f, 0.13f, 0.12f)
            }

            // Rule teks sederhana (✓, -, Sudah, Belum) diprioritaskan. Formula custom
            // seperti perhitungan total dikirim setelahnya supaya jika formula itu gagal,
            // warna ceklis dan status tetap terpasang.
            val simpleFormatRules = JSONArray()
            val customFormulaRules = JSONArray()
            for (index in 0 until batchRequests.length()) {
                val request = batchRequests.getJSONObject(index)
                val conditionType = request
                    .optJSONObject("addConditionalFormatRule")
                    ?.optJSONObject("rule")
                    ?.optJSONObject("booleanRule")
                    ?.optJSONObject("condition")
                    ?.optString("type")
                if (conditionType == "CUSTOM_FORMULA") {
                    customFormulaRules.put(request)
                } else {
                    simpleFormatRules.put(request)
                }
            }
            submitFormattingBatch(simpleFormatRules, "warna status dan ceklis")
            submitFormattingBatch(customFormulaRules, "warna formula lanjutan")
            return sep
        } catch (e: Exception) {
            e.printStackTrace()
            return sep
        }
    }

    private fun createConditionalFormatRule(
        sheetId: Int,
        startRow: Int,
        endRow: Int,
        startCol: Int,
        endCol: Int,
        conditionType: String,
        conditionValue: String,
        bgRed: Float, bgGreen: Float, bgBlue: Float,
        textRed: Float, textGreen: Float, textBlue: Float,
        index: Int,
        bold: Boolean = true
    ): JSONObject {
        return JSONObject().apply {
            put("addConditionalFormatRule", JSONObject().apply {
                put("rule", JSONObject().apply {
                    put("ranges", JSONArray().apply {
                        put(JSONObject().apply {
                            put("sheetId", sheetId)
                            put("startRowIndex", startRow)
                            put("endRowIndex", endRow)
                            put("startColumnIndex", startCol)
                            put("endColumnIndex", endCol)
                        })
                    })
                    put("booleanRule", JSONObject().apply {
                        put("condition", JSONObject().apply {
                            put("type", conditionType)
                            put("values", JSONArray().apply {
                                put(JSONObject().apply { put("userEnteredValue", conditionValue) })
                            })
                        })
                        put("format", JSONObject().apply {
                            put("backgroundColor", JSONObject().apply {
                                put("red", bgRed)
                                put("green", bgGreen)
                                put("blue", bgBlue)
                            })
                            put("textFormat", JSONObject().apply {
                                put("foregroundColor", JSONObject().apply {
                                    put("red", textRed)
                                    put("green", textGreen)
                                    put("blue", textBlue)
                                })
                                put("bold", bold)
                            })
                        })
                    })
                })
                put("index", index)
            })
        }
    }

    private fun createColumnStyleRequest(
        sheetId: Int,
        startCol: Int,
        endCol: Int = startCol + 1,
        startRow: Int = 1,
        endRow: Int = 1000,
        bgRed: Float? = null,
        bgGreen: Float? = null,
        bgBlue: Float? = null,
        textRed: Float? = null,
        textGreen: Float? = null,
        textBlue: Float? = null,
        bold: Boolean? = null,
        numberFormatType: String? = null,
        numberFormatPattern: String? = null
    ): JSONObject {
        val fieldMasks = mutableListOf("userEnteredFormat.horizontalAlignment", "userEnteredFormat.verticalAlignment")
        val uFormat = JSONObject().apply {
            put("horizontalAlignment", "CENTER")
            put("verticalAlignment", "MIDDLE")
            if (bgRed != null && bgGreen != null && bgBlue != null) {
                put("backgroundColor", JSONObject().apply {
                    put("red", bgRed)
                    put("green", bgGreen)
                    put("blue", bgBlue)
                })
                fieldMasks.add("userEnteredFormat.backgroundColor")
            }
            val textFmt = JSONObject()
            var hasTextFmt = false
            if (textRed != null && textGreen != null && textBlue != null) {
                textFmt.put("foregroundColor", JSONObject().apply {
                    put("red", textRed)
                    put("green", textGreen)
                    put("blue", textBlue)
                })
                hasTextFmt = true
            }
            if (bold != null) {
                textFmt.put("bold", bold)
                hasTextFmt = true
            }
            if (hasTextFmt) {
                put("textFormat", textFmt)
                fieldMasks.add("userEnteredFormat.textFormat")
            }
            if (numberFormatType != null) {
                put("numberFormat", JSONObject().apply {
                    put("type", numberFormatType)
                    if (numberFormatPattern != null) {
                        put("pattern", numberFormatPattern)
                    }
                })
                fieldMasks.add("userEnteredFormat.numberFormat")
            }
        }

        return JSONObject().apply {
            put("repeatCell", JSONObject().apply {
                put("range", JSONObject().apply {
                    put("sheetId", sheetId)
                    put("startRowIndex", startRow)
                    put("endRowIndex", endRow)
                    put("startColumnIndex", startCol)
                    put("endColumnIndex", endCol)
                })
                put("cell", JSONObject().apply {
                    put("userEnteredFormat", uFormat)
                })
                put("fields", fieldMasks.joinToString(","))
            })
        }
    }

    private fun createCenterAlignmentRequest(sheetId: Int, numCols: Int, startRowIndex: Int = 1, endRowIndex: Int = 1000): JSONObject {
        return JSONObject().apply {
            put("repeatCell", JSONObject().apply {
                put("range", JSONObject().apply {
                    put("sheetId", sheetId)
                    put("startRowIndex", startRowIndex)
                    put("endRowIndex", endRowIndex)
                    put("startColumnIndex", 0)
                    put("endColumnIndex", numCols)
                })
                put("cell", JSONObject().apply {
                    put("userEnteredFormat", JSONObject().apply {
                        put("horizontalAlignment", "CENTER")
                        put("verticalAlignment", "MIDDLE")
                    })
                })
                put("fields", "userEnteredFormat.horizontalAlignment,userEnteredFormat.verticalAlignment")
            })
        }
    }

    private fun createResetEmptyRowsRequest(sheetId: Int, startRowIndex: Int, numCols: Int): JSONObject {
        return JSONObject().apply {
            put("repeatCell", JSONObject().apply {
                put("range", JSONObject().apply {
                    put("sheetId", sheetId)
                    put("startRowIndex", startRowIndex)
                    put("endRowIndex", 1000)
                    put("startColumnIndex", 0)
                    put("endColumnIndex", numCols)
                })
                put("cell", JSONObject().apply {
                    // Kosongkan format bawaan pada baris tanpa data. Conditional
                    // formatting tidak ikut terhapus karena merupakan rule terpisah.
                    put("userEnteredFormat", JSONObject())
                })
                put("fields", "userEnteredFormat")
            })
        }
    }

    /**
     * Memastikan seluruh sel di setiap sheet (Ringkasan Harian, Ringkasan Mingguan, Rekap Waktu, Data Mentah)
     * memiliki perataan Rata Tengah (Center & Middle) dan pewarnaan kolom metadata yang estetis & harmonis,
     * HANYA pada baris yang memiliki data (tidak bablas sampai baris 1000).
     */
    private fun applyCellStylingAndAlignments(
        token: String,
        spreadsheetId: String,
        ringkasanRowCount: Int,
        mingguanRowCount: Int,
        rekapWaktuRowCount: Int,
        rawRowCount: Int
    ) {
        try {
            val getUrl = "$SHEETS_API_BASE/$spreadsheetId?fields=sheets(properties(sheetId,title))"
            val getReq = Request.Builder()
                .url(getUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val batchRequests = JSONArray()

            httpClient.newCall(getReq).execute().use { res ->
                if (res.isSuccessful) {
                    val root = JSONObject(res.body?.string() ?: "")
                    val sheets = root.optJSONArray("sheets") ?: return
                    for (i in 0 until sheets.length()) {
                        val prop = sheets.getJSONObject(i).getJSONObject("properties")
                        val sId = prop.getInt("sheetId")
                        val title = prop.getString("title")

                        when (title) {
                            "Ringkasan Harian", "Ringkasan" -> {
                                val endR = maxOf(ringkasanRowCount, 2)
                                // Kolom 0 (Tanggal): background #FAFAFA, text #202124, DATE pattern
                                batchRequests.put(createColumnStyleRequest(
                                    sheetId = sId, startCol = 0, startRow = 1, endRow = endR,
                                    bgRed = 0.98f, bgGreen = 0.98f, bgBlue = 0.98f,
                                    textRed = 0.13f, textGreen = 0.13f, textBlue = 0.14f,
                                    numberFormatType = "DATE", numberFormatPattern = "d MMMM yyyy"
                                ))
                                // Kolom 1..5 (Salat): center & middle
                                batchRequests.put(createCenterAlignmentRequest(sId, 6, 1, endR))
                                // Kolom 6 (Total Selesai): center & bold (warna background & teks diatur dinamis oleh Conditional Formatting)
                                batchRequests.put(createColumnStyleRequest(
                                    sheetId = sId, startCol = 6, startRow = 1, endRow = endR,
                                    bgRed = 1.0f, bgGreen = 1.0f, bgBlue = 1.0f,
                                    bold = true
                                ))
                                // Bersihkan baris kosong di bawahnya (endR..1000) agar tidak ada warna sisa
                                if (endR < 1000) {
                                    batchRequests.put(createResetEmptyRowsRequest(sId, endR, 7))
                                }
                            }
                            "Ringkasan Mingguan" -> {
                                val endR = maxOf(mingguanRowCount, 3)
                                // Tiga kolom identitas minggu dibedakan lembut agar
                                // blok ringkasan mudah dipindai dari kiri ke kanan.
                                batchRequests.put(createColumnStyleRequest(
                                    sheetId = sId, startCol = 0, startRow = 2, endRow = endR,
                                    bgRed = 0.91f, bgGreen = 0.96f, bgBlue = 1.0f,
                                    textRed = 0.10f, textGreen = 0.30f, textBlue = 0.58f
                                ))
                                batchRequests.put(createColumnStyleRequest(
                                    sheetId = sId, startCol = 1, startRow = 2, endRow = endR,
                                    bgRed = 0.95f, bgGreen = 0.93f, bgBlue = 1.0f,
                                    textRed = 0.33f, textGreen = 0.20f, textBlue = 0.62f
                                ))
                                batchRequests.put(createColumnStyleRequest(
                                    sheetId = sId, startCol = 2, startRow = 2, endRow = endR,
                                    bgRed = 1.0f, bgGreen = 0.96f, bgBlue = 0.87f,
                                    textRed = 0.58f, textGreen = 0.32f, textBlue = 0.02f
                                ))
                                // Kolom 3..37 (35 sel salat): center & middle
                                batchRequests.put(createCenterAlignmentRequest(sId, 38, 2, endR))
                                // Kolom 38 (Total Selesai): center & bold (warna background & teks diatur dinamis oleh Conditional Formatting)
                                batchRequests.put(createColumnStyleRequest(
                                    sheetId = sId, startCol = 38, startRow = 2, endRow = endR,
                                    bgRed = 1.0f, bgGreen = 1.0f, bgBlue = 1.0f,
                                    bold = true
                                ))
                                // Bersihkan baris kosong di bawahnya (endR..1000) agar tidak ada warna sisa
                                if (endR < 1000) {
                                    batchRequests.put(createResetEmptyRowsRequest(sId, endR, 39))
                                }
                            }
                            "Rekap Waktu" -> {
                                val endR = maxOf(rekapWaktuRowCount, 2)
                                // Kolom 0 (Tanggal): background #FAFAFA, text #202124, DATE pattern
                                batchRequests.put(createColumnStyleRequest(
                                    sheetId = sId, startCol = 0, startRow = 1, endRow = endR,
                                    bgRed = 0.98f, bgGreen = 0.98f, bgBlue = 0.98f,
                                    textRed = 0.13f, textGreen = 0.13f, textBlue = 0.14f,
                                    numberFormatType = "DATE", numberFormatPattern = "d MMMM yyyy"
                                ))
                                // Kolom 1..5 (Salat): center & middle, TEXT
                                batchRequests.put(createColumnStyleRequest(
                                    sheetId = sId, startCol = 1, endCol = 6, startRow = 1, endRow = endR,
                                    numberFormatType = "TEXT"
                                ))
                                // Kolom 6 (Total Selesai): center & bold (warna background & teks diatur dinamis oleh Conditional Formatting)
                                batchRequests.put(createColumnStyleRequest(
                                    sheetId = sId, startCol = 6, startRow = 1, endRow = endR,
                                    bgRed = 1.0f, bgGreen = 1.0f, bgBlue = 1.0f,
                                    bold = true
                                ))
                                // Bersihkan baris kosong di bawahnya (endR..1000) agar tidak ada warna sisa
                                if (endR < 1000) {
                                    batchRequests.put(createResetEmptyRowsRequest(sId, endR, 7))
                                }
                            }
                            "Data Mentah" -> {
                                val endR = maxOf(rawRowCount, 2)
                                // Metadata diberi warna pastel yang berbeda per fungsi agar
                                // mudah dibaca tanpa merebut fokus dari Status/Keterangan.
                                // Kolom 0 (ID): slate abu-biru, teks redup, TEXT
                                batchRequests.put(createColumnStyleRequest(
                                    sheetId = sId, startCol = 0, startRow = 1, endRow = endR,
                                    bgRed = 0.93f, bgGreen = 0.95f, bgBlue = 0.97f,
                                    textRed = 0.28f, textGreen = 0.34f, textBlue = 0.42f,
                                    numberFormatType = "TEXT"
                                ))
                                // Kolom 1 (Tanggal): biru muda, DATE pattern "yyyy-MM-dd"
                                batchRequests.put(createColumnStyleRequest(
                                    sheetId = sId, startCol = 1, startRow = 1, endRow = endR,
                                    bgRed = 0.91f, bgGreen = 0.96f, bgBlue = 1.0f,
                                    textRed = 0.10f, textGreen = 0.30f, textBlue = 0.58f,
                                    numberFormatType = "DATE", numberFormatPattern = "yyyy-MM-dd"
                                ))
                                // Kolom 2 (Salat): mint, teks hijau, bold
                                batchRequests.put(createColumnStyleRequest(
                                    sheetId = sId, startCol = 2, startRow = 1, endRow = endR,
                                    bgRed = 0.89f, bgGreen = 0.97f, bgBlue = 0.92f,
                                    textRed = 0.08f, textGreen = 0.38f, textBlue = 0.22f,
                                    bold = true
                                ))
                                // Kolom 3 (Jadwal Masuk): ungu muda
                                batchRequests.put(createColumnStyleRequest(
                                    sheetId = sId, startCol = 3, startRow = 1, endRow = endR,
                                    bgRed = 0.95f, bgGreen = 0.93f, bgBlue = 1.0f,
                                    textRed = 0.33f, textGreen = 0.20f, textBlue = 0.62f
                                ))
                                // Kolom 4 (Batas Akhir): amber muda sebagai penanda batas waktu
                                batchRequests.put(createColumnStyleRequest(
                                    sheetId = sId, startCol = 4, startRow = 1, endRow = endR,
                                    bgRed = 1.0f, bgGreen = 0.96f, bgBlue = 0.87f,
                                    textRed = 0.58f, textGreen = 0.32f, textBlue = 0.02f
                                ))
                                // Kolom 5 (Jam Selesai): cyan muda
                                batchRequests.put(createColumnStyleRequest(
                                    sheetId = sId, startCol = 5, startRow = 1, endRow = endR,
                                    bgRed = 0.89f, bgGreen = 0.98f, bgBlue = 0.99f,
                                    textRed = 0.02f, textGreen = 0.40f, textBlue = 0.45f
                                ))
                                // Kolom 6 & 7 (Status & Keterangan): center & middle (warna ditangani conditional formatting)
                                batchRequests.put(createCenterAlignmentRequest(sId, 8, 1, endR))
                                // Bersihkan baris kosong di bawahnya (endR..1000) agar tidak ada warna sisa
                                if (endR < 1000) {
                                    batchRequests.put(createResetEmptyRowsRequest(sId, endR, 8))
                                }
                            }
                            else -> {
                                batchRequests.put(createCenterAlignmentRequest(sId, 10, 1, 1000))
                            }
                        }
                    }
                }
            }

            if (batchRequests.length() > 0) {
                val batchUrl = "$SHEETS_API_BASE/$spreadsheetId:batchUpdate"
                val payload = JSONObject().apply {
                    put("requests", batchRequests)
                }
                val batchReq = Request.Builder()
                    .url(batchUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
                httpClient.newCall(batchReq).execute().use { response ->
                    if (!response.isSuccessful) {
                        val detail = response.body?.string() ?: ""
                        android.util.Log.e("GoogleSheetsSync", "Gagal menerapkan layout sheet (${response.code}): $detail")
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun createHeaderFormatRequest(sheetId: Int, numCols: Int, numRows: Int = 1): JSONObject {
        return JSONObject().apply {
            put("repeatCell", JSONObject().apply {
                put("range", JSONObject().apply {
                    put("sheetId", sheetId)
                    put("startRowIndex", 0)
                    put("endRowIndex", numRows)
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
                put("fields", "userEnteredFormat.numberFormat,userEnteredFormat.horizontalAlignment,userEnteredFormat.verticalAlignment")
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
                put("fields", "userEnteredFormat.numberFormat,userEnteredFormat.horizontalAlignment,userEnteredFormat.verticalAlignment")
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

    private fun parseTimeToEpoch(dateStr: String, timeStr: String): Long =
        PrayerDateTimeUtils.parseTimeToEpoch(dateStr, timeStr)

    private fun normalizeSheetDate(raw: String): String =
        PrayerDateTimeUtils.normalizeSheetDate(raw)

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
