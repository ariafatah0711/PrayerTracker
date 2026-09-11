package com.prayertracker.app.core.sync

import com.prayertracker.app.core.database.AppDatabase
import com.prayertracker.app.core.datastore.AppSettingsRepository
import com.prayertracker.app.core.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncCoordinator(
    private val database: AppDatabase,
    private val driveBackupManager: GoogleDriveBackupManager,
    private val sheetsSyncManager: GoogleSheetsSyncManager,
    private val settingsRepository: AppSettingsRepository
) {

    suspend fun performFullSync(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 1. Silent Backup to Google Drive appDataFolder (Safe Snapshot)
            val backupResult = driveBackupManager.backupToDrive()
            if (backupResult.isFailure) {
                return@withContext Result.failure(backupResult.exceptionOrNull() ?: Exception("Backup failed"))
            }

            // 2. Export / Update Google Sheets (Laptop View)
            val sheetsResult = sheetsSyncManager.syncToSheets()
            val sheetUrl = sheetsResult.getOrNull()

            val now = System.currentTimeMillis()

            // 3. Mark pending prayers as SYNCED
            val pendingPrayers = database.prayerRecordDao().getPendingSyncPrayers()
            pendingPrayers.forEach { p ->
                database.prayerRecordDao().updateStatus(
                    id = p.id,
                    status = p.status,
                    completedAt = p.completedAtEpoch,
                    updatedAt = p.updatedAtEpoch,
                    syncStatus = SyncStatus.SYNCED
                )
            }

            // 4. Update sync timestamp and spreadsheet URL in preferences
            settingsRepository.updateSyncInfo(now, sheetUrl)

            Result.success(sheetUrl ?: "Sync completed")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreData(): Result<Int> = withContext(Dispatchers.IO) {
        driveBackupManager.restoreFromDrive()
    }
}
