package com.kaavalan.note.data.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * v2.1.0 (PM rating): the WorkManager-driven daily
 * Google Drive backup. Mirrors the v1.8.0
 * [com.kaavalan.note.data.export.BackupWorker] pattern.
 *
 * **The recovery phrase problem.** A daily
 * background worker can't prompt the user for the
 * 12-word recovery phrase (the worker runs even when
 * the screen is off). For v2.1.0, the passphrase is
 * looked up from [com.kaavalan.note.data.auth.SecurePreferences]
 * — the user set it in Settings on first backup, and
 * the worker re-uses it for every subsequent auto-
 * backup. (The v2.1.0 UX is: "first time you tap
 * 'Back up now', the Settings sheet prompts for the
 * passphrase + saves it. The daily worker re-uses
 * the saved passphrase.")
 *
 * **The failure mode.** If the user revoked the
 * app's Drive access in their Google account
 * settings, [GoogleOAuthClient.getAccessToken]
 * returns `null` and the worker bails out with
 * [Result.failure] (v2.1.1 — was [Result.retry] in
 * v2.1.0). The Settings sheet re-renders the
 * "Sign in" CTA on the next launch.
 *
 * **v2.1.1 (security): why [Result.failure] not
 * [Result.retry] on `NotSignedIn`.** v2.1.0 returned
 * [Result.retry] for `NotSignedIn` so the worker
 * would re-run on the next 24h tick. The problem:
 * [KaavalanApplication.onCreate] scheduled the worker
 * on every cold start (idempotent, KEEP policy),
 * which meant the worker fired within seconds of
 * the launcher on a device that had never signed
 * in. Each fire wrote a `failure` result to the
 * WorkManager log and, if the user had configured
 * the drive-verify test, dumped a stack trace. The
 * fix: gate the schedule on `isSignedIn() && has-passphrase`
 * in [KaavalanApplication.onCreate], and return
 * [Result.failure] (with a `Log.w`) on `NotSignedIn`
 * so the worker doesn't pollute the log on a device
 * that never signed in. The user re-enables the
 * schedule the next time they sign in.
 */
@HiltWorker
class DriveBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val driveBackupManager: DriveBackupManager,
    private val securePreferences: com.kaavalan.note.data.auth.SecurePreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // The key material was stored on the first manual
        // backup: `BackupCrypto.keyMaterialFor(phrase)`, i.e.
        // the SHA-256 hex of the recovery phrase.
        //
        // v2.2.1: that is now the *only* key source. Before it,
        // `googleDriveBackUpNow` encrypted with the raw phrase
        // while this worker used the hash, so an automatic
        // backup could not be opened with the phrase the
        // restore dialog asks for. The comment that stood here
        // said the user could read the hash off "a 'Backup
        // settings' page" — no such page existed, and
        // `getBackupEncryptionKeyHash` had no reader anywhere
        // outside this worker, so the key was genuinely
        // unobtainable and every automatic backup was lost.
        // Restore now derives the same value from the phrase.
        val passphraseHash = securePreferences.getBackupEncryptionKeyHash()
            ?: return Result.failure(
                androidx.work.workDataOf(
                    "reason" to "no-passphrase-set",
                ),
            )
        return try {
            val file = driveBackupManager.backUpWithKeyMaterial(
                keyMaterial = passphraseHash.toCharArray(),
            )
            if (file.sizeBytes > 0) Result.success() else Result.retry()
        } catch (e: DriveBackupManager.DriveBackupException.NotSignedIn) {
            // v2.1.1 (security): the user revoked access
            // or the silent sign-in failed. The KaavalanNoteApp
            // gates the periodic schedule on isSignedIn()
            // — this branch only fires if the user signed
            // out between the schedule and the fire. Log
            // + failure; the user re-enables the schedule
            // on next sign-in.
            android.util.Log.w(
                TAG,
                "DriveBackupWorker: not signed in; skipping " +
                    "(next manual back-up will re-enable)",
            )
            Result.failure(
                androidx.work.workDataOf(
                    "reason" to "not-signed-in",
                ),
            )
        } catch (e: Throwable) {
            Result.failure(
                androidx.work.workDataOf(
                    "error" to (e.message ?: e::class.java.simpleName),
                ),
            )
        }
    }

    companion object {
        private const val TAG = "KaavalanDriveBackupWorker"
    }
}
