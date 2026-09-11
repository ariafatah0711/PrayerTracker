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
            // 0. Cloud-First Safety Net: Hanya pulihkan snapshot dari Google Drive jika
            // database lokal benar-benar kosong (misalnya setelah login di HP baru atau reset).
            // Jika sudah ada data lokal, Google Sheets adalah sumber kebenaran utama.
            val localCount = database.prayerRecordDao().getAllPrayers().size
            if (localCount == 0) {
                try {
                    driveBackupManager.restoreFromDrive()
                } catch (_: Exception) {
                    // Abaikan jika belum ada backup Google Drive sebelumnya
                }
            }

            // 1. Two-way sync with Google Sheets (pull remote edits & merge with local)
            val sheetsResult = sheetsSyncManager.syncToSheets()
            if (sheetsResult.isFailure) {
                return@withContext Result.failure(sheetsResult.exceptionOrNull() ?: Exception("Gagal sinkronisasi Google Sheets"))
            }
            val sheetUrl = sheetsResult.getOrThrow()

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

            Result.success(sheetUrl)
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

    // Opsi 1: Tarik data Cloud ke HP (Cloud -> Lokal)
    suspend fun syncCloudToLocal(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 1. Tarik perubahan terbaru langsung dari Google Sheets (sumber kebenaran utama cloud)
            val sheetsResult = sheetsSyncManager.pullFromSheets()
            val sheetUrl = if (sheetsResult.isSuccess) {
                sheetsResult.getOrNull()
            } else {
                // Fallback jika belum ada spreadsheet: coba pulihkan snapshot Drive JSON jika ada
                val driveRes = driveBackupManager.restoreFromDrive()
                if (driveRes.isFailure) {
                    return@withContext Result.failure(sheetsResult.exceptionOrNull() ?: Exception("Gagal menarik data dari Google Sheets"))
                }
                null
            }

            // 2. Sekarang database lokal telah diperbarui dengan data Sheets terbaru.
            // Buat backup Drive JSON yang selaras dengan data yang baru ditarik ini
            try {
                driveBackupManager.backupToDrive()
            } catch (_: Exception) {}

            val now = System.currentTimeMillis()
            settingsRepository.updateSyncInfo(now, sheetUrl)
            notifyDataRefreshed()

            val successMsg = if (sheetUrl != null) {
                "Berhasil menarik dan menerapkan data terbaru dari Google Sheets ke HP!"
            } else {
                "Berhasil memulihkan data dari cadangan Google Drive ke HP!"
            }
            Result.success(successMsg)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Opsi 2: Unggah data HP ke Cloud (Lokal -> Cloud)
    suspend fun syncLocalToCloud(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Langsung timpa / perbarui Sheets dan Drive dengan data lokal saat ini
            val sheetsResult = sheetsSyncManager.syncToSheets(skipRemotePull = true)
            if (sheetsResult.isFailure) {
                return@withContext Result.failure(sheetsResult.exceptionOrNull() ?: Exception("Gagal membuat/memperbarui Google Sheets"))
            }
            val sheetUrl = sheetsResult.getOrThrow()
            driveBackupManager.backupToDrive()

            val now = System.currentTimeMillis()
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
            settingsRepository.updateSyncInfo(now, sheetUrl)
            notifyDataRefreshed()

            Result.success(sheetUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Opsi 3: Pindahkan cadangan Cloud (Drive & Spreadsheet) ke Sampah
    suspend fun trashCloudData(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            driveBackupManager.trashCloudBackup()
            sheetsSyncManager.trashSpreadsheet()
            settingsRepository.updateSyncInfo(null, null)
            notifyDataRefreshed()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
