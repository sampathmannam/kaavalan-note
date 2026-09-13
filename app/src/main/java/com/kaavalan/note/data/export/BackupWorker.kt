package com.kaavalan.note.data.export

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * v1.8.0 (PROD-READINESS-P0-#1): the WorkManager-driven daily
 * backup. Calls [BackupManager.backup] and reports the result
 * via WorkManager's standard `Result.success` / `Result.failure`
 * channel.
 *
 * The worker is scheduled by [com.kaavalan.note.data.work.WorkManagerInitializer.scheduleBackup]
 * (periodic, 24 h) or by [com.kaavalan.note.data.work.WorkManagerInitializer.enqueueBackupNow]
 * (one-shot, from the Settings sheet "Back up now" button).
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backupManager: BackupManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            backupManager.backup()
            // An empty first-launch snapshot is still a valid backup.
            Result.success()
        } catch (e: CancellationException) {
            // WorkManager cancellation must remain cooperative.
            throw e
        } catch (e: Exception) {
            // v1.8.0: log + retry. The user-facing toast /
            // notification lives in the WorkManager observer
            // that the Settings sheet sets up.
            Result.retry()
        }
    }
}
