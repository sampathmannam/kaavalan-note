package com.kaavalan.note.data.backup

import android.util.Log
import com.kaavalan.note.data.export.PlainExporter
import com.kaavalan.note.data.export.PlainImporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v2.1.0 (PM rating): the Google Drive backup
 * orchestrator. The user said "like WhatsApp" — the
 * local DB stays on the device, a backup goes to
 * Google Drive automatically, and the user can
 * restore on a new device.
 *
 * **The flow.**
 *
 *  1. **Sign-in.** [GoogleOAuthClient] opens a Chrome
 *     Custom Tab to Google's OAuth page. The user
 *     signs in + grants the `drive.appdata` scope.
 *     Google redirects to a custom scheme Kaavalan note
 *     catches; the auth code is exchanged for an
 *     access + refresh token. The refresh token is
 *     stored in `SecurePreferences`; the access token
 *     is held in memory.
 *
 *  2. **Back up now.** [backUpNow] takes a snapshot
 *     via [PlainExporter], encrypts the bytes via
 *     [BackupCrypto] (PBKDF2 from the recovery
 *     phrase), uploads via [DriveRestApi] to the
 *     user's `appDataFolder`. The Drive file ID is
 *     returned.
 *
 *  3. **Restore from list.** [listBackups] enumerates
 *     the `appDataFolder`. [restore] picks one by ID,
 *     downloads, decrypts, and imports via
 *     [PlainImporter].
 *
 *  4. **Daily auto-backup.** [DriveBackupWorker] runs
 *     on a 24h WorkManager schedule and calls
 *     [backUpWithKeyMaterial].
 *
 * **Encryption is the key choice.** The bytes are
 * encrypted client-side before upload. Even Google
 * (or a future attacker with the user's Google
 * account) cannot read the bytes without the recovery
 * phrase.
 */
@Singleton
class DriveBackupManager @Inject constructor(
    private val httpClient: io.ktor.client.HttpClient,
    private val plainExporter: PlainExporter,
    private val plainImporter: PlainImporter,
    private val crypto: BackupCrypto,
    private val oauth: GoogleOAuthClient,
) {

    private val driveApi = DriveRestApi(httpClient)

    /**
     * Back up the local DB to the user's Drive
     * `appDataFolder`. Returns the new Drive file
     * [DriveRestApi.DriveFile].
     *
     * @param passphrase the user's recovery phrase, as typed.
     *   Derivation to key material happens here rather than
     *   at the call site — see [backUpWithKeyMaterial].
     */
    suspend fun backUpNow(
        passphrase: CharArray,
    ): DriveRestApi.DriveFile {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        return backUpWithKeyMaterial(BackupCrypto.keyMaterialFor(passphrase))
    }

    /**
     * The same backup, for a caller that already holds key
     * material rather than the phrase — i.e.
     * [DriveBackupWorker], which runs at 3am and has only the
     * value `SecurePreferences` stored.
     *
     * **Why the two entry points exist.** Until v2.2.1 there
     * was one method taking "a passphrase", and the two callers
     * disagreed about what that meant.
     * `SettingsViewModel.googleDriveBackUpNow` handed it the raw
     * phrase; the worker handed it the SHA-256 hash. PBKDF2 over
     * a phrase and PBKDF2 over that phrase's hash are different
     * keys, so every automatic backup was encrypted under a key
     * the restore dialog never asks for — and the hash it would
     * have opened with was displayed nowhere in the app. The
     * backups uploaded, listed, and reported a plausible size,
     * and were unrecoverable.
     *
     * Two names, each stating what it takes, is what stops that
     * recurring. [backUpNow] derives; this one does not.
     *
     * @param keyMaterial the value [BackupCrypto.keyMaterialFor]
     *   produces, which is also exactly what
     *   `SecurePreferences.setBackupEncryptionKeyHash` stores.
     */
    suspend fun backUpWithKeyMaterial(
        keyMaterial: CharArray,
    ): DriveRestApi.DriveFile {
        require(keyMaterial.isNotEmpty()) { "passphrase must not be empty" }
        // 1. Refresh the access token (silent sign-in).
        val accessToken = oauth.getAccessToken()
            ?: throw DriveBackupException.NotSignedIn()

        // 2. Snapshot.
        val snapshot = plainExporter.snapshot()
        val json = plainExporter.toJson(snapshot).toByteArray(Charsets.UTF_8)

        // 3. Encrypt.
        val blob = crypto.encrypt(json, keyMaterial)

        // 4. Upload.
        val fileName = "kaavalan-note-backup-${ts()}.json.enc"
        val id = driveApi.uploadToAppFolder(accessToken, fileName, blob)
        Log.i(
            TAG,
            "Backed up ${blob.size} bytes to Drive file '$fileName' (id=$id)",
        )
        return DriveRestApi.DriveFile(
            id = id,
            name = fileName,
            sizeBytes = blob.size.toLong(),
            createdTimeMs = System.currentTimeMillis(),
        )
    }

    /**
     * List the existing Kaavalan note backups in the user's
     * `appDataFolder`, newest first. Same shape as
     * [com.kaavalan.note.data.export.BackupManager.listBackups].
     */
    suspend fun listBackups(): List<DriveRestApi.DriveFile> {
        val accessToken = oauth.getAccessToken()
            ?: throw DriveBackupException.NotSignedIn()
        return driveApi.listBackups(accessToken)
    }

    /**
     * Restore from a specific Drive backup by ID. The
     * downloaded bytes are decrypted with
     * [recoveryPhrase] and imported into the local DB via
     * [PlainImporter]. Returns the
     * [PlainImporter.ImportReport] so the caller can show
     * inserted/updated counts in a snackbar.
     *
     * @param recoveryPhrase what the user typed into the
     *   restore dialog — the phrase itself, not key
     *   material. [BackupCrypto.decryptWithRecoveryPhrase]
     *   derives from it, and falls back to the pre-v2.2.1
     *   derivation for older blobs.
     */
    suspend fun restore(
        fileId: String,
        recoveryPhrase: CharArray,
    ): PlainImporter.ImportReport {
        require(recoveryPhrase.isNotEmpty()) { "passphrase must not be empty" }
        val accessToken = oauth.getAccessToken()
            ?: throw DriveBackupException.NotSignedIn()
        val bytes = driveApi.downloadFile(accessToken, fileId)
        val json = try {
            crypto.decryptWithRecoveryPhrase(bytes, recoveryPhrase)
        } catch (e: Throwable) {
            throw DriveBackupException.WrongPassphrase(e)
        }
        // The crypto output is the JSON bytes; we
        // import via [PlainImporter] which parses the
        // JSON. We use a `tempFile` so the importer
        // gets a real [java.io.File] to read from.
        val tmp = kotlin.io.path.createTempFile(suffix = ".json").toFile()
        try {
            tmp.writeBytes(json)
            return plainImporter.importFromUri(android.net.Uri.fromFile(tmp))
                .getOrThrow()
        } finally {
            tmp.delete()
        }
    }

    /**
     * Delete a Drive backup by ID. Idempotent (a 404
     * is treated as success).
     */
    suspend fun deleteBackup(fileId: String) {
        val accessToken = oauth.getAccessToken()
            ?: throw DriveBackupException.NotSignedIn()
        driveApi.deleteFile(accessToken, fileId)
    }

    private fun ts(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    sealed class DriveBackupException(message: String) : RuntimeException(message) {
        class NotSignedIn : DriveBackupException("Not signed in to Google.")
        class WrongPassphrase(cause: Throwable) :
            DriveBackupException("The passphrase did not decrypt the backup. " +
                "(The bytes may be corrupted, or the passphrase is wrong.)")
    }

    private companion object {
        private const val TAG = "KaavalanNoteDriveBackup"
    }
}
