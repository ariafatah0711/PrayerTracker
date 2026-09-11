package com.prayertracker.app.core.sync

import com.prayertracker.app.core.database.AppDatabase
import com.prayertracker.app.core.datastore.AppSettingsRepository
import com.prayertracker.app.core.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

class SyncCoordinator(
    private val database: AppDatabase,
    private val driveBackupManager: GoogleDriveBackupManager,
    private val sheetsSyncManager: GoogleSheetsSyncManager,
    private val settingsRepository: AppSettingsRepository
) {
    private val _dataRefreshEvent = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val dataRefreshEvent: SharedFlow<Unit> = _dataRefreshEvent.asSharedFlow()

    fun notifyDataRefreshed() {
        _dataRefreshEvent.tryEmit(Unit)
    }

    suspend fun performFullSync(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 0. Cloud-First Merge: Pulihkan snapshot dari Google Drive terlebih dahulu jika ada.
            // Langkah ini menjamin bahwa jika pengguna login di HP baru atau setelah reset lokal,
            // seluruh data riwayat masa lalu di Cloud tidak akan pernah tertimpa oleh data lokal yang masih kosong!
            try {
                driveBackupManager.restoreFromDrive()
            } catch (_: Exception) {
                // Abaikan jika belum ada backup Google Drive sebelumnya
            }

            // 1. Two-way sync with Google Sheets (pull remote edits & merge with local)
            val sheetsResult = sheetsSyncManager.syncToSheets()
            val sheetUrl = sheetsResult.getOrNull()

            // 2. Sekarang database lokal telah berisi gabungan utuh (Merged) dari Drive + Sheets + Local.
            // Simpan snapshot aman gabungan ini kembali ke Google Drive appDataFolder
            driveBackupManager.backupToDrive()

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

            // 5. Notify active ViewModels to reload data immediately
            notifyDataRefreshed()

            Result.success(sheetUrl ?: "Sync completed")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreData(): Result<Int> = withContext(Dispatchers.IO) {
        val result = driveBackupManager.restoreFromDrive()
        if (result.isSuccess) {
            notifyDataRefreshed()
        }
        result
    }
}
