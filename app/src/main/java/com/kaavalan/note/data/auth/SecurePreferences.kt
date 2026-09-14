package com.kaavalan.note.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M3-T1: secure storage of the local database encryption key.
 *
 * The Room DB is opened through SQLCipher (see [com.kaavalan.note.di.DatabaseModule]).
 * SQLCipher needs a 32-byte passphrase; we generate it on first launch
 * with [SecureRandom] and persist it in [EncryptedSharedPreferences],
 * which is itself protected by a Keystore-backed master key (Android
 * 6+ hardware-backed where available).
 *
 * **Threat model.** The passphrase protects the on-disk Room DB from
 * being read by:
 *
 *  - An attacker with physical access to the device (cold-boot, ADB
 *    pull of `/data/data/com.kaavalan.note/databases/kaavalan-note.db`).
 *  - Backup extraction (Android's auto-backup is disabled in this
 *    app via `android:allowBackup="false"`, but a future change that
 *    re-enables it doesn't leak the DB).
 *
 * It does **not** protect against:
 *
 *  - An attacker who has compromised the app process (Keystore
 *    unwraps the key on demand; the running app can read it).
 *  - An attacker who can read the Keystore master key (requires root
 *    + screen-unlock on modern Android, but a strong adversary could
 *    bypass it).
 *
 * For the v1 pilot the threat model is "someone steals the phone and
 * `adb pull`s the SQLite file". SQLCipher + EncryptedSharedPreferences
 * blocks that. The user-visible passphrase flow (Settings → Export /
 * Re-encrypt) is deferred to v1.1 (see spec §16, "Out of scope").
 *
 * **Sign-out.** [clearDatabasePassphrase] deletes the key. The next
 * read of the DB fails (SQLCipher reports "file is not a database");
 * the AppInitializer then catches the failure, opens a fresh
 * unencrypted DB, and the user starts clean. Sync replays from
 * Supabase the next time they're online.
 */
@Singleton
class SecurePreferences private constructor(
    context: Context,
    preferencesFileName: String,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context, FILE_NAME)

    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        preferencesFileName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /**
     * Returns the SQLCipher passphrase bytes. On first call (or after
     * a sign-out / clear), generates a fresh 32-byte key, persists
     * it, and returns it. The returned byte array is freshly allocated
     * each call so callers can zero it after use if they care.
     */
    @Synchronized
    fun databasePassphrase(): ByteArray {
        val stored = prefs.getString(KEY_DB_PASSPHRASE, null)
        if (stored != null) {
            return Base64.decode(stored, Base64.NO_WRAP)
        }
        val fresh = ByteArray(PASSPHRASE_BYTES)
        SecureRandom().nextBytes(fresh)
        prefs.edit()
            .putString(KEY_DB_PASSPHRASE, Base64.encodeToString(fresh, Base64.NO_WRAP))
            .apply()
        return fresh
    }

    /**
     * Sign-out path: delete the DB passphrase. The next DB read fails
     * and the caller ([com.kaavalan.note.data.local.AppInitializer]) is
     * expected to wipe the old `kaavalan-note.db` file so a fresh unencrypted
     * one is created. RLS on the Supabase side ensures the next sign-in
     * pulls back only the new user's data.
     *
     * Returns `true` if a passphrase was present and removed; `false`
     * if the store was already empty.
     */
    @Synchronized
    fun clearDatabasePassphrase(): Boolean {
        if (!prefs.contains(KEY_DB_PASSPHRASE)) return false
        prefs.edit().remove(KEY_DB_PASSPHRASE).apply()
        return true
    }

    /**
     * Has the user set a passphrase yet? `true` after the first
     * [databasePassphrase] call, `false` on a fresh install. Useful
     * for the AppInitializer's first-run branch (the old plain DB
     * needs to be wiped on the M2 -> M3 transition).
     */
    fun hasDatabasePassphrase(): Boolean = prefs.contains(KEY_DB_PASSPHRASE)

    /**
     * v2.1.0 (PM rating): the Google Drive backup
     * tokens. The refresh token is the long-lived
     * credential that survives process death; the
     * access token is short-lived (~1h) and is cached
     * in-memory in [GoogleOAuthClient]. The expiry is
     * the epoch-millis at which the cached access
     * token must be refreshed.
     *
     * These are stored in [SecurePreferences] (not
     * plain SharedPreferences) because the refresh
     * token is a long-lived credential that can
     * impersonate the user on Drive.
     */
    fun setGoogleRefreshToken(token: String) {
        prefs.edit().putString(KEY_GOOGLE_REFRESH_TOKEN, token).apply()
    }

    fun getGoogleRefreshToken(): String? = prefs.getString(KEY_GOOGLE_REFRESH_TOKEN, null)

    fun setGoogleAccessTokenExpiry(epochMillis: Long) {
        prefs.edit().putLong(KEY_GOOGLE_ACCESS_EXPIRY, epochMillis).apply()
    }

    fun getGoogleAccessTokenExpiry(): Long = prefs.getLong(KEY_GOOGLE_ACCESS_EXPIRY, 0L)

    fun clearGoogleTokens() {
        prefs.edit()
            .remove(KEY_GOOGLE_REFRESH_TOKEN)
            .remove(KEY_GOOGLE_ACCESS_EXPIRY)
            .apply()
    }

    /**
     * v2.1.0 (PM rating): the SHA-256 hash of the
     * passphrase used to encrypt the Google Drive
     * backup blob. The passphrase itself is the
     * user's 12-word recovery phrase; we never store
     * the phrase in clear. The hash is the actual
     * key material the worker feeds to
     * [com.kaavalan.note.data.backup.BackupCrypto].
     *
     * Set once on the first manual "Back up now". The
     * daily [com.kaavalan.note.data.backup.DriveBackupWorker]
     * reads this and re-uses it for every subsequent
     * auto-backup. The user can rotate the passphrase
     * (which forces the next backup to be encrypted
     * with the new hash); the old backups on Drive
     * remain decryptable with their old hash.
     */
    fun setBackupEncryptionKeyHash(hash: String) {
        prefs.edit().putString(KEY_BACKUP_KEY_HASH, hash).apply()
    }

    fun getBackupEncryptionKeyHash(): String? =
        prefs.getString(KEY_BACKUP_KEY_HASH, null)

    /**
     * v2.0 T3-1: the SHA-256 hash of the user's vault PIN, stored
     * as a hex string. `null` means the user has not set a PIN
     * yet; in that case the Settings UI prompts them to set one
     * before they can switch back from hidden -> visible.
     *
     * The hash is stored (not the PIN itself) so a forensic
     * adversary with read access to the EncryptedSharedPreferences
     * file cannot recover the PIN. The hash is deterministic so
     * a re-entered PIN is comparable in O(1).
     */
    @Synchronized
    fun vaultPinHash(): String? = prefs.getString(KEY_VAULT_PIN_HASH, null)

    /**
     * v2.0 T3-1: store the SHA-256 of [pin]. The caller is
     * expected to have already validated the PIN (length, no
     * whitespace) — this method just persists.
     */
    @Synchronized
    fun setVaultPinHash(hash: String) {
        prefs.edit().putString(KEY_VAULT_PIN_HASH, hash).apply()
    }

    /** v2.0 T3-1: clear the stored PIN hash (e.g. on user
     *  request, or as part of the sign-out wipe). */
    @Synchronized
    fun clearVaultPinHash() {
        prefs.edit().remove(KEY_VAULT_PIN_HASH).apply()
    }

    /**
     * v2.0 T3-2 (recovery phrase): the SHA-256 of the
     * space-joined phrase, stored as a hex string. `null` means
     * the user has not generated a phrase yet. The phrase itself
     * is NEVER persisted — only the hash, so the on-device store
     * cannot be used to reconstruct the master secret.
     *
     * Regenerating the phrase (Settings -> Recovery phrase ->
     * Regenerate) overwrites this value and the old phrase is
     * effectively orphaned.
     */
    @Synchronized
    fun recoveryPhraseHash(): String? = prefs.getString(KEY_RECOVERY_PHRASE_HASH, null)

    /** v2.0 T3-2: store the SHA-256 of the (space-joined)
     *  recovery phrase. */
    @Synchronized
    fun setRecoveryPhraseHash(hash: String) {
        prefs.edit().putString(KEY_RECOVERY_PHRASE_HASH, hash).apply()
    }

    /** v2.0 T3-2: clear the recovery phrase hash (e.g. when the
     *  user regenerates the phrase). */
    @Synchronized
    fun clearRecoveryPhraseHash() {
        prefs.edit().remove(KEY_RECOVERY_PHRASE_HASH).apply()
    }

    companion object {
        /**
         * Creates an instance backed by an isolated file for on-device tests.
         * Production injection always uses [FILE_NAME].
         */
        @androidx.annotation.VisibleForTesting
        fun forTesting(context: Context, preferencesFileName: String): SecurePreferences =
            SecurePreferences(context, preferencesFileName)

        private const val FILE_NAME = "kaavalan_note_secure_prefs"
        private const val KEY_DB_PASSPHRASE = "db_passphrase_v1"
        // v2.1.0 (PM rating): the Google Drive backup
        // tokens. See [setGoogleRefreshToken] /
        // [getGoogleRefreshToken] /
        // [setGoogleAccessTokenExpiry] /
        // [getGoogleAccessTokenExpiry] /
        // [clearGoogleTokens].
        private const val KEY_GOOGLE_REFRESH_TOKEN = "google_refresh_token_v1"
        private const val KEY_GOOGLE_ACCESS_EXPIRY = "google_access_expiry_v1"
        // v2.1.0: the SHA-256 hash of the user's
        // backup passphrase. Set on first manual
        // "Back up now"; the daily worker reads it
        // for every subsequent auto-backup.
        private const val KEY_BACKUP_KEY_HASH = "backup_key_hash_v1"
        private const val PASSPHRASE_BYTES = 32
        // v2.0 T3-1: vault PIN hash. Hex string, 64 chars
        // (SHA-256 = 32 bytes). Key name includes a `_v1` to
        // let us change the hash algorithm later without a
        // destructive migration.
        private const val KEY_VAULT_PIN_HASH = "vault_pin_hash_v1"
        // v2.0 T3-2: recovery phrase hash. Same format as the
        // vault PIN hash. The phrase is space-joined before
        // hashing so the hash is stable across whitespace
        // normalisation.
        private const val KEY_RECOVERY_PHRASE_HASH = "recovery_phrase_hash_v1"
    }
}
