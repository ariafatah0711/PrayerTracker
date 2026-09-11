package com.prayertracker.app.core.sync

import com.prayertracker.app.core.database.AppDatabase
import com.prayertracker.app.core.model.PrayerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class GoogleSheetsSyncManager(
    private val database: AppDatabase,
    private val authManager: GoogleAuthManager
) {
    private val httpClient = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    companion object {
        private const val SPREADSHEET_TITLE = "Prayer Tracker - Catatan Ibadah"
        private const val SHEETS_API_URL = "https://sheets.googleapis.com/v4/spreadsheets"
        private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
    }

    suspend fun syncToSheets(): Result<String> = withContext(Dispatchers.IO) {
        val token = authManager.getAccessToken()
            ?: return@withContext Result.failure(IllegalStateException("Not authenticated with Google"))

        try {
            // 1. Find or create spreadsheet
            val spreadsheetId = findOrCreateSpreadsheet(token)

            // 2. Fetch all local records
            val missedPrayers = database.prayerRecordDao().getMissedPrayers()
            val pendingSync = database.prayerRecordDao().getPendingSyncPrayers()
            val combined = (missedPrayers + pendingSync).distinctBy { it.id }

            // 3. Prepare rows
            val rows = JSONArray()

            // Header row
            val headerRow = JSONArray().apply {
                put("Tanggal")
                put("Salat")
                put("Jadwal")
                put("Status")
                put("Selesai Jam")
                put("Status Qadha")
                put("Catatan")
            }
            rows.put(headerRow)

            // Data rows
            combined.forEach { p ->
                val scheduledStr = Instant.ofEpochMilli(p.scheduledTimeEpoch)
                    .atZone(ZoneId.systemDefault())
                    .format(timeFormatter)

                val completedStr = p.completedAtEpoch?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(timeFormatter)
                } ?: "-"

                val row = JSONArray().apply {
                    put(p.prayerDate)
                    put(p.prayerName.displayName)
                    put(scheduledStr)
                    put(p.status.displayName)
                    put(completedStr)
                    put(if (p.status == PrayerStatus.QADHA_COMPLETED) "Selesai Qadha" else if (p.status == PrayerStatus.MISSED) "Belum Qadha" else "-")
                    put(if (p.status == PrayerStatus.COMPLETED) "Tepat Waktu" else if (p.status == PrayerStatus.MISSED) "Perlu Qadha" else "")
                }
                rows.put(row)
            }

            // 4. Update values in Google Sheets
            val updatePayload = JSONObject().apply {
                put("range", "Sheet1!A1:G${rows.length()}")
                put("majorDimension", "ROWS")
                put("values", rows)
            }

            val updateUrl = "$SHEETS_API_URL/$spreadsheetId/values/Sheet1!A1:G${rows.length()}?valueInputOption=USER_ENTERED"
            val updateRequest = Request.Builder()
                .url(updateUrl)
                .addHeader("Authorization", "Bearer $token")
                .put(updatePayload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(updateRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Failed to update Google Sheet: ${response.code}"))
                }
            }

            val webUrl = "https://docs.google.com/spreadsheets/d/$spreadsheetId"
            Result.success(webUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun findOrCreateSpreadsheet(token: String): String {
        // Check if spreadsheet already exists in Drive
        val query = "name = '$SPREADSHEET_TITLE' and mimeType = 'application/vnd.google-apps.spreadsheet' and trashed = false"
        val url = "$DRIVE_FILES_URL?q=${java.net.URLEncoder.encode(query, "UTF-8")}"

        val searchRequest = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        httpClient.newCall(searchRequest).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "")
                val files = json.optJSONArray("files")
                if (files != null && files.length() > 0) {
                    return files.getJSONObject(0).getString("id")
                }
            }
        }

        // Create new spreadsheet
        val createPayload = JSONObject().apply {
            put("properties", JSONObject().apply {
                put("title", SPREADSHEET_TITLE)
            })
        }

        val createRequest = Request.Builder()
            .url(SHEETS_API_URL)
            .addHeader("Authorization", "Bearer $token")
            .post(createPayload.toString().toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(createRequest).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "")
                return json.getString("spreadsheetId")
            } else {
                throw Exception("Failed to create spreadsheet: ${response.code}")
            }
        }
    }
}
