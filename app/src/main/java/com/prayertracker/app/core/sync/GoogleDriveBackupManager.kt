package com.prayertracker.app.core.sync

import com.prayertracker.app.core.database.AppDatabase
import com.prayertracker.app.core.database.entity.PrayerRecordEntity
import com.prayertracker.app.core.database.entity.QadhaRecordEntity
import com.prayertracker.app.core.database.entity.ReminderLogEntity
import com.prayertracker.app.core.model.PrayerName
import com.prayertracker.app.core.model.PrayerStatus
import com.prayertracker.app.core.model.ReminderType
import com.prayertracker.app.core.model.SyncStatus
import com.prayertracker.app.core.model.UserResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class GoogleDriveBackupManager(
    private val database: AppDatabase,
    private val authManager: GoogleAuthManager
) {
    private val httpClient = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val BACKUP_FILENAME = "prayer_tracker_backup.json"
        private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
        private const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
    }

    suspend fun backupToDrive(): Result<String> = withContext(Dispatchers.IO) {
        val token = authManager.getAccessToken() ?: return@withContext Result.failure(IllegalStateException("Not authenticated with Google"))

        try {
            // 1. Serialize Room DB to JSON
            val backupJson = createBackupJson()

            // 2. Check if backup file already exists in appDataFolder
            val existingFileId = findExistingBackupFileId(token)

            if (existingFileId != null) {
                // Update existing file
                val updateUrl = "$DRIVE_UPLOAD_URL/$existingFileId?uploadType=media"
                val request = Request.Builder()
                    .url(updateUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .patch(backupJson.toString().toRequestBody(jsonMediaType))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("Failed to update backup on Drive: ${response.code}"))
                    }
                }
            } else {
                // Create new multipart file in appDataFolder
                val metadata = JSONObject().apply {
                    put("name", BACKUP_FILENAME)
                    put("parents", JSONArray().put("appDataFolder"))
                }

                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("metadata", null, metadata.toString().toRequestBody(jsonMediaType))
                    .addFormDataPart("file", BACKUP_FILENAME, backupJson.toString().toRequestBody(jsonMediaType))
                    .build()

                val request = Request.Builder()
                    .url("$DRIVE_UPLOAD_URL?uploadType=multipart")
                    .addHeader("Authorization", "Bearer $token")
                    .post(multipartBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("Failed to create backup in Drive: ${response.code}"))
                    }
                }
            }

            Result.success("Backup to Google Drive completed successfully")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreFromDrive(): Result<Int> = withContext(Dispatchers.IO) {
        val token = authManager.getAccessToken() ?: return@withContext Result.failure(IllegalStateException("Not authenticated with Google"))

        try {
            val fileId = findExistingBackupFileId(token) ?: return@withContext Result.failure(NoSuchElementException("No existing backup file found in Google Drive"))

            val downloadUrl = "$DRIVE_FILES_URL/$fileId?alt=media"
            val request = Request.Builder()
                .url(downloadUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Failed to download backup: ${response.code}"))
                }

                val responseBody = response.body?.string() ?: return@withContext Result.failure(Exception("Empty backup file"))
                val backupJson = JSONObject(responseBody)

                val restoredCount = restoreFromJson(backupJson)
                Result.success(restoredCount)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun createBackupJson(): JSONObject {
        val prayers = database.prayerRecordDao().getPrayersForDate("9999-12-31") // dummy or get all
        // Let's get all prayers directly from DAO or query
        val allPrayers = database.prayerRecordDao().getPendingSyncPrayers()
        val missed = database.prayerRecordDao().getMissedPrayers()

        val root = JSONObject()
        root.put("version", 1)
        root.put("timestamp", System.currentTimeMillis())

        val prayerArray = JSONArray()
        // Collect prayer records
        missed.forEach { p ->
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

        return root
    }

    private suspend fun restoreFromJson(root: JSONObject): Int {
        val prayersArray = root.optJSONArray("prayers") ?: return 0
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
        return entities.size
    }

    private fun findExistingBackupFileId(token: String): String? {
        val query = "name = '$BACKUP_FILENAME' and 'appDataFolder' in parents and trashed = false"
        val url = "$DRIVE_FILES_URL?spaces=appDataFolder&q=${java.net.URLEncoder.encode(query, "UTF-8")}"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "")
                val files = json.optJSONArray("files")
                if (files != null && files.length() > 0) {
                    return files.getJSONObject(0).getString("id")
                }
            }
        }
        return null
    }
}
