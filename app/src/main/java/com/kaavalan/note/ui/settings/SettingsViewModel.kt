package com.kaavalan.note.ui.settings





import android.content.Context

import android.net.Uri

import com.kaavalan.note.data.export.PlainExporter
import com.kaavalan.note.data.export.PlainImporter
import com.kaavalan.note.data.backup.DriveBackupManager

import com.kaavalan.note.data.local.AppDatabase

import com.kaavalan.note.data.preferences.KaavalanPreferences

import com.kaavalan.note.data.preferences.ThemeMode

import dagger.hilt.android.qualifiers.ApplicationContext

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.flow.flowOn

import kotlinx.coroutines.withContext

import androidx.lifecycle.ViewModel


import androidx.lifecycle.viewModelScope


import com.kaavalan.note.BuildConfig




import com.kaavalan.note.data.auth.SecurePreferences


import com.kaavalan.note.data.local.AppInitializer


import com.kaavalan.note.data.local.InstructionDao


import com.kaavalan.note.data.local.PersonDao




import com.kaavalan.note.data.local.TagDao




import com.kaavalan.note.data.tags.RoomTagRepository


import com.kaavalan.note.data.tags.Tag


import com.kaavalan.note.data.vault.IdentityCrypto


import com.kaavalan.note.data.vault.VaultMode


import com.kaavalan.note.data.vault.VaultModeHolder


import dagger.hilt.android.lifecycle.HiltViewModel


import kotlinx.coroutines.flow.MutableStateFlow


import kotlinx.coroutines.flow.SharingStarted


import kotlinx.coroutines.flow.StateFlow


import kotlinx.coroutines.flow.asStateFlow


import kotlinx.coroutines.flow.combine


import kotlinx.coroutines.flow.stateIn


import kotlinx.coroutines.launch


import javax.inject.Inject


import kotlinx.coroutines.flow.map

import java.io.File



/**


 * M3-T4: Settings view-model. The single action for M3-T4 was sign-out.


 * M3-T7 adds a tag management surface: the sheet shows the user's tags


 * (a small chips list grouped by kind) plus a free-form entry to add


 * a new FREE tag.


 *


 * **Sign-out order.** [AppInitializer.runOnSignOut] MUST run before


 * [AuthRepository.signOut] returns, otherwise the in-flight Compose


 * tree (HomeScreen + SettingsSheet) still has Hilt references to


 * the database and would observe a "database is not a database"


 * error from SQLCipher. We run them in the right order here:


 * wipe first (synchronous, cheap), then drop the session (which


 * triggers the observer).


 *


 * v1.2 (BUG-AUTH-002): also close the Realtime WebSocket BEFORE


 * `authRepository.signOut()` so the previous user's JWT does not


 * stay on the wire after the local DB is wiped. The next


 * sign-in re-calls [RealtimeSync.start] with the new token.


 */


@HiltViewModel


class SettingsViewModel @Inject constructor(


    private val appInitializer: AppInitializer,


    private val tagRepository: RoomTagRepository,


    private val personDao: PersonDao,


    private val instructionDao: InstructionDao,


    private val tagDao: TagDao,


    // v2.0 T3-1: deniable vault. The holder is a process


    // singleton; the ViewModel reads its current mode and


    // exposes a [setVaultMode] entry point for the Settings


    // sheet. The vault PIN (the auth gate on the


    // hidden -> visible transition) is stored in


    // [securePreferences] as a SHA-256 hash.


    private val vaultModeHolder: VaultModeHolder,


    private val securePreferences: SecurePreferences,

    private val preferences: KaavalanPreferences,

    private val plainExporter: PlainExporter,
    // v2.0.1 (PM rating): the importer is the inverse of
    // [plainExporter]. The Settings sheet's "Import"
    // button uses it to round-trip a v1.x export back
    // into the local DB.
    private val plainImporter: PlainImporter,
    // v2.1.0 (PM rating): the Google Drive backup. The
    // Settings sheet renders a "Sign in with Google" /
    // "Back up now" / "Restore" surface that uses these
    // three classes. The OAuth flow is the Custom Tabs
    // path (no play-services-auth dep).
    private val driveBackupManager: com.kaavalan.note.data.backup.DriveBackupManager,
    private val googleOAuthClient: com.kaavalan.note.data.backup.GoogleOAuthClient,

    // v1.9.0 (PROD-READINESS-P3-P1-#8 + #9):
    // the BackupManager is exposed so the
    // Settings sheet's "Back up to Google
    // Drive" / "Restore from backup" rows
    // can copy the latest cached backup
    // to / from the SAF-chosen URI.
    val backupManager: com.kaavalan.note.data.export.BackupManager,

    // v1.9.0 (PROD-READINESS-P3-P1-#3): the
    // in-app update channel. The Settings sheet
    // "Check for updates" row calls
    // [checkForUpdates]; the result is exposed
    // as a one-shot flow so the UI can render
    // a snackbar with the latest check.
    private val updateChecker: com.kaavalan.note.data.update.UpdateChecker,

    // v1.6.2: the developer-only synthetic data loader.
    private val fixtureLoader: com.kaavalan.note.data.dev.FixtureLoader,  // v1.6.2

    // v1.8.0 (PROD-READINESS-P2-#2): the sync-conflict
    // DAO. The v1.5.0 vault-mode build has no cloud
    // sync, so the table is always empty. The VM
    // exposes the count for the Settings sheet; the
    // row is hidden when the count is 0.
    private val syncConflictDao: com.kaavalan.note.data.local.SyncConflictDao,
    // v2.1.0 (PM rating): the database-health flag.
    // `true` when the preflight detected a runtime
    // corruption on the last launch.
    private val databaseHealth: com.kaavalan.note.data.local.DatabaseHealth,
    @ApplicationContext private val appContext: Context,


) : ViewModel() {





    private val _signingOut = MutableStateFlow(false)


    val signingOut: StateFlow<Boolean> = _signingOut.asStateFlow()





    /**


     * v1.2.4 (F-HIGH-08): number of outbox rows that have hit


     * [SyncEngine.MAX_ATTEMPTS] and are stuck


     * (`PERMANENT_FAILURE:*`). 0 means "no stuck rows" — the


     * UI hides the retry card. The flow re-emits on every


     * change (new stuck row, retry, or successful drain of a


     * previously-stuck row).


     */


    val stuckOutboxCount: StateFlow<Int> = MutableStateFlow(0)  // v2.0.0: no sync engine
        .asStateFlow()

    /**
     * v2.0.2 (PM rating): the database-corruption banner
     * state. `true` when [com.kaavalan.note.data.local.DatabasePreflight]
     * detected a runtime DB open failure on the last
     * launch (wrong passphrase, corrupt file, etc.).
     * The Settings sheet reads this and surfaces a
     * "Database error — tap to erase and start fresh"
     * banner with a one-tap path to [eraseAllLocalData].
     */
    val databaseCorrupt: StateFlow<Boolean> = MutableStateFlow(
        databaseHealth.isCorrupt(),
    ).asStateFlow()

    /**
     * v2.1.0 (PM rating): the Google Drive sign-in
     * state. `true` when a refresh token is stored
     * (i.e. the user has gone through the Custom Tabs
     * OAuth flow at least once). The Settings sheet
     * reads this on every open + after the OAuth
     * callback activity finishes; the UI re-renders
     * the "Sign in" / "Back up now" / "Sign out" surface
     * accordingly.
     */
    val googleDriveSignedIn: StateFlow<Boolean> = MutableStateFlow(
        googleOAuthClient.isSignedIn(),
    ).asStateFlow()

    /**
     * v2.1.0: one-shot events for the Settings sheet's
     * Drive backup surface. The [DriveBackupEvent] is a
     * sealed class so the UI can branch on success /
     * error / needs-passphrase without parsing strings.
     * The [consume] helper clears the event after the
     * UI renders it.
     */
    sealed class DriveBackupEvent {
        object SignInRequired : DriveBackupEvent()
        object PassphraseRequired : DriveBackupEvent()
        data class BackUpSuccess(val fileId: String, val fileName: String) : DriveBackupEvent()
        data class BackUpFailed(val reason: String) : DriveBackupEvent()
        data class RestoreSucceeded(val rows: Int) : DriveBackupEvent()
        data class RestoreFailed(val reason: String) : DriveBackupEvent()
        data class BackupsListed(val files: List<com.kaavalan.note.data.backup.DriveRestApi.DriveFile>) : DriveBackupEvent()
        data class WrongPassphrase(val message: String) : DriveBackupEvent()
    }

    private val _driveBackupEvent = kotlinx.coroutines.flow.MutableSharedFlow<DriveBackupEvent>(extraBufferCapacity = 1)
    val driveBackupEvent: kotlinx.coroutines.flow.SharedFlow<DriveBackupEvent> = _driveBackupEvent

    /**
     * v2.1.0: refresh the "signed in" state. Called by
     * the Settings sheet on resume (so a successful
     * OAuth round-trip is reflected in the UI). Also
     * called after the user taps "Sign out".
     */
    fun refreshGoogleDriveSignedIn() {
        (googleDriveSignedIn as MutableStateFlow).value = googleOAuthClient.isSignedIn()
    }

    /**
     * v2.1.0: open the Custom Tabs OAuth flow. The
     * activity bounces the user to Google's OAuth
     * page, then back to the OAuthCallbackActivity.
     * On return, the user is signed in (or not — if
     * they cancelled).
     */
    fun googleDriveSignIn() {
        googleOAuthClient.signIn()
    }

    /**
     * v2.1.0: clear the stored refresh token + in-memory
     * access token. The Settings sheet re-renders the
     * "Sign in" CTA.
     */
    fun googleDriveSignOut() {
        googleOAuthClient.signOut()
        refreshGoogleDriveSignedIn()
    }

    /**
     * v2.1.0: back up the local DB to Drive. [passphrase]
     * is the user's 12-word recovery phrase (space-joined);
     * its SHA-256 hash is what the worker re-uses for
     * daily auto-backups. The hash is stored in
     * [SecurePreferences] so the user only has to enter
     * the phrase once.
     */
    fun googleDriveBackUpNow(passphrase: String) {
        viewModelScope.launch {
            try {
                if (passphrase.isBlank()) {
                    _driveBackupEvent.emit(DriveBackupEvent.PassphraseRequired)
                    return@launch
                }
                if (!googleOAuthClient.isSignedIn()) {
                    _driveBackupEvent.emit(DriveBackupEvent.SignInRequired)
                    return@launch
                }
                val hash = com.kaavalan.note.data.vault.IdentityCrypto
                    .sha256Hex(passphrase)
                securePreferences.setBackupEncryptionKeyHash(hash)
                val phraseChars = passphrase.toCharArray()
                val file = try {
                    driveBackupManager.backUpNow(phraseChars)
                } finally {
                    phraseChars.fill('\u0000')
                }
                _driveBackupEvent.emit(
                    DriveBackupEvent.BackUpSuccess(file.id, file.name),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _driveBackupEvent.emit(
                    DriveBackupEvent.BackUpFailed(
                        com.kaavalan.note.ui.util.SafeError.forUser(
                            e,
                            "Drive backup failed. Try again.",
                        ),
                    ),
                )
            }
        }
    }

    /**
     * v2.1.0: list the existing Drive backups. The
     * Settings sheet shows the result in an
     * AlertDialog so the user can pick one to restore.
     */
    fun googleDriveListBackups() {
        viewModelScope.launch {
            try {
                if (!googleOAuthClient.isSignedIn()) {
                    _driveBackupEvent.emit(DriveBackupEvent.SignInRequired)
                    return@launch
                }
                val files = driveBackupManager.listBackups()
                _driveBackupEvent.emit(DriveBackupEvent.BackupsListed(files))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _driveBackupEvent.emit(
                    DriveBackupEvent.BackUpFailed("Could not list Drive backups. Try again."),
                )
            }
        }
    }

    /**
     * v2.1.0: restore from a specific Drive backup by
     * ID. The user enters the recovery phrase that
     * was used to encrypt the backup; the same
     * encryption key is required to decrypt.
     */
    fun googleDriveRestore(fileId: String, passphrase: String) {
        viewModelScope.launch {
            try {
                if (passphrase.isBlank()) {
                    _driveBackupEvent.emit(DriveBackupEvent.PassphraseRequired)
                    return@launch
                }
                val phraseChars = passphrase.toCharArray()
                val report = try {
                    driveBackupManager.restore(fileId, phraseChars)
                } finally {
                    phraseChars.fill('\u0000')
                }
                _driveBackupEvent.emit(
                    DriveBackupEvent.RestoreSucceeded(report.total),
                )
            } catch (e: com.kaavalan.note.data.backup.DriveBackupManager.DriveBackupException.WrongPassphrase) {
                _driveBackupEvent.emit(DriveBackupEvent.WrongPassphrase("The recovery phrase is incorrect."))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _driveBackupEvent.emit(
                    DriveBackupEvent.RestoreFailed(
                        com.kaavalan.note.ui.util.SafeError.forUser(
                            e,
                            "Drive restore failed. Nothing was changed.",
                        ),
                    ),
                )
            }
        }
    }





    /**


     * M3-T7: the user's tag taxonomy, observed from the Room mirror.


     * Sorted by usageCount DESC then name ASC inside the DAO. The


     * sheet groups these by kind for the user.


     */


    val tags: StateFlow<List<Tag>> = tagRepository


        .observeAll()


        .stateIn(


            scope = viewModelScope,


            started = SharingStarted.WhileSubscribed(5_000),


            initialValue = emptyList(),


        )





    /**


     * v1.5.3 (VAULT-008): the "On this phone" storage card. Live


     * count of people + instructions + tags. The number is


     * recomputed on every local write (Room is reactive), so the


     * card updates as the user adds / removes rows.


     */


            .stateIn(


            scope = viewModelScope,


            started = SharingStarted.WhileSubscribed(5_000),


            initialValue = emptyList(),


        )





    /**


     * v1.5.3 (VAULT-008): the "On this phone" storage card. Live


     * count of people + instructions + tags. The number is


     * recomputed on every local write (Room is reactive), so the


     * card updates as the user adds / removes rows.


     *


     * Tier 0.6: the same flow also recomputes the on-disk


     * size in bytes (DB file + WAL + SHM + photos dir). The


     * bytes are computed off the main thread; the


     * `WhileSubscribed(5_000)` policy keeps the upstream


     * active across config changes.


     */


    val storage: StateFlow<StorageInfo> = combine(


        personDao.observeAll(),


        instructionDao.observeAll(),


        tagDao.observeAll(),


    ) { persons, instructions, tags ->


        Triple(persons.size, instructions.size, tags.size)


    }.map { (people, instructions, tags) ->


        val bytes = computeStorageSizeBytes()


        StorageInfo(


            peopleCount = people,


            instructionCount = instructions,


            tagCount = tags,


            sizeBytes = bytes,


        )


    }.flowOn(Dispatchers.IO).stateIn(


        scope = viewModelScope,


        started = SharingStarted.WhileSubscribed(5_000),


        initialValue = StorageInfo(),


    )





    /**


     * v1.5.3 (VAULT-008): the app version string. BuildConfig


     * has the versionName + versionCode; we expose them as a


     * tuple so the UI can render "{versionName} (build


     * {versionCode})" without re-reading BuildConfig.


     */


    val appVersion: AppVersion = AppVersion(

        name = BuildConfig.VERSION_NAME,

        code = BuildConfig.VERSION_CODE,


    )








    fun signOut() {


        if (_signingOut.value) return


        _signingOut.value = true


        viewModelScope.launch {


            // Order matters:


            // 1. Close Realtime — the previous user's JWT must not


            //    stay on the WebSocket (BUG-AUTH-002 / BATON-WIRE-002).


            // 2. Wipe the local DB — the Compose tree still references


            //    SQLCipher via Hilt; the wipe must finish before the


            //    session observer tears the activity down.


            // 3. Sign out of Supabase — the local sign-out is


            //    effective even if the network call fails (the


            //    server-side refresh-token revocation is best-effort).


            // realtimeSync.stop() -- v2.0.0: no realtime


            runCatching { appInitializer.runOnSignOut() }


            // authRepository.signOut() -- v2.0.0: no auth; clear local data instead
            // v2.0.0: in a local-only app, sign-out is a "wipe local" operation


            // The session observer in MainActivity will transition


            // to Unauthenticated and the Compose tree will tear


            // down the HomeScreen + SettingsSheet automatically.


            // We don't need to dismiss the sheet ourselves.


        }


    }





    /**


     * M3-T7: create a new FREE tag from the management surface. Same


     * path the LLM extractor will use when it surfaces a `#tag` it


     * hasn't seen before.


     */


    fun addFreeTag(name: String) {


        val clean = name.trim().trimStart('#').take(40)


        if (clean.isBlank()) return


        viewModelScope.launch {


            runCatching { tagRepository.findOrCreateFree(clean) }


        }


    }





    /**


     * v1.2.4 (F-HIGH-08): reset all `PERMANENT_FAILURE:*` rows


     * in the outbox so the next drain retries them. Called from


     * the "Retry stuck entries" action in the Settings sheet.


     * The next periodic drain (15 minutes) or the next


     * per-write drain will process them with `attempts = 0`.


     *


     * No UI feedback is wired yet — the Settings sheet


     * observes [stuckOutboxCount] and the count drops to 0 on


     * success. A snackbar is a natural follow-up but it's


     * out of scope for this fix.


     */


    fun retryStuckOutbox() {


        viewModelScope.launch {


            runCatching { /* syncEngine.retryPermanentlyFailed() -- v2.0.0: no sync engine */ Unit }


        }


    }





    /**


     * v2.0 T3-1: the active vault mode, observed from the


     * process-singleton [VaultModeHolder]. The Settings sheet


     * renders this value as the "Vault mode" row.


     */


    val vaultMode: StateFlow<VaultMode> = vaultModeHolder.mode





    /**


     * v2.0 T3-1: `true` once the user has set a vault PIN.


     * The Settings sheet gates the "switch to visible" flow


     * on this — a user without a PIN is prompted to set one


     * first. The underlying MutableStateFlow is re-emitted


     * by [setVaultPin] / [clearVaultPin] so the UI updates


     * without a process restart.


     */


    private val _hasVaultPin = MutableStateFlow(


        securePreferences.vaultPinHash() != null,


    )


    val hasVaultPin: StateFlow<Boolean> = _hasVaultPin.asStateFlow()





    /**


     * v2.0 T3-2: `true` once the user has generated a recovery


     * phrase. The Settings sheet renders a "Set up recovery


     * phrase" affordance when this is `false`; once it's


     * `true`, the affordance becomes "View recovery phrase"


     * (which still requires the PIN to re-display).


     */


    private val _hasRecoveryPhrase = MutableStateFlow(


        securePreferences.recoveryPhraseHash() != null,


    )


    val hasRecoveryPhrase: StateFlow<Boolean> = _hasRecoveryPhrase.asStateFlow()





    /**


     * v2.0 T3-1: set the vault PIN. The user supplies a 4-6


     * digit PIN; we SHA-256 it and store the hash. The PIN


     * itself is never persisted.


     *


     * @return `true` if the PIN was set, `false` if the input


     *   failed validation (empty or not digits).


     */


    fun setVaultPin(pin: String): Boolean {


        if (!isValidPin(pin)) return false


        securePreferences.setVaultPinHash(IdentityCrypto.sha256Hex(pin))


        // Re-emit the `hasVaultPin` flow so the Settings sheet


        // updates without a process restart.


        _hasVaultPin.value = securePreferences.vaultPinHash() != null


        return true


    }





    /**


     * v2.0 T3-1: switch the active vault mode. The caller is


     * responsible for the PIN gate when the target mode is


     * [VaultMode.Visible] AND the user has a PIN set —


     * [pinMatches] should be checked first.


     *


     * The PIN is not an argument here because we want the


     * UI to drive the flow: it asks the user for the PIN,


     * checks it, then calls [setVaultMode] with the result.


     */


    fun setVaultMode(mode: VaultMode) {


        vaultModeHolder.setMode(mode)


    }





    /**


     * v2.0 T3-1: validate a candidate PIN against the stored


     * hash. Returns `true` iff the user has a PIN set AND


     * [pin]'s SHA-256 matches. Returns `false` on no-PIN-set


     * (caller should call [setVaultPin] first).


     */


    fun pinMatches(pin: String): Boolean {


        val stored = securePreferences.vaultPinHash() ?: return false


        return IdentityCrypto.sha256Hex(pin) == stored


    }





    /**


     * v2.0 T3-1: clear the vault PIN hash. Used by the


     * "Erase all data" path (in addition to [AppInitializer]


     * wiping the DB). The vault mode is reset to


     * [VaultMode.Visible] by the holder on the next


     * sign-in.


     */


    fun clearVaultPin() {


        securePreferences.clearVaultPinHash()


        _hasVaultPin.value = false


    }





    /**


     * v2.0 T3-2: the recovery phrase hash was just set by the


     * [com.kaavalan.note.ui.privacy.RecoveryPhraseViewModel]. We


     * re-emit [hasRecoveryPhrase] so the Settings sheet's


     * "View recovery phrase" affordance appears without a


     * process restart.


     */


    fun onRecoveryPhraseChanged() {


        _hasRecoveryPhrase.value = securePreferences.recoveryPhraseHash() != null


    }





    /**


     * v2.0 (Tier 1.4): expose the theme mode as a


     * [StateFlow]. The Settings sheet renders a segmented


     * button that mirrors the value. The


     * [KaavalanPreferences.setThemeMode] call persists to


     * DataStore; the root composable reads the same flow so


     * the swap is immediate.


     */


    val themeMode: StateFlow<ThemeMode> = preferences.themeMode


        .stateIn(


            scope = viewModelScope,


            started = SharingStarted.Eagerly,


            initialValue = ThemeMode.System,


        )



    fun setThemeMode(mode: ThemeMode) {


        viewModelScope.launch { preferences.setThemeMode(mode) }


    }



    /**


     * v2.0 (Tier 1.7): export the DB as CSV or JSON to the


     * given SAF URI. The MIME type decides the format:


     *   - `text/csv`  → CSV with UTF-8 BOM


     *   - `application/json` → JSON


     * Other MIME types fall through to JSON. The call


     * returns `Result<Unit>` so the sheet can show a


     * user-friendly error if the write fails.


     */


    suspend fun exportPlain(uri: Uri, mime: String?): Result<Unit> = runCatching {


        val snap = plainExporter.snapshot()


        val bytes = when (mime) {


            "text/csv" -> plainExporter.toCsv(snap).toByteArray(Charsets.UTF_8)


            else -> plainExporter.toJson(snap).toByteArray(Charsets.UTF_8)


        }


        val out = appContext.contentResolver.openOutputStream(uri, "wt")


            ?: error("Could not open output")


        out.use { it.write(bytes) }


    }

    /**
     * v2.0.1 (PM rating): the inverse of [exportPlain].
     * Reads a CSV or JSON snapshot from the SAF-chosen
     * URI and upserts every row. The MIME type is
     * ignored — the importer branches on the first
     * non-whitespace character (`{` → JSON, else → CSV).
     *
     * Returns the [PlainImporter.ImportReport] on
     * success, or the underlying [Throwable] on
     * failure. Callers render the report as a snackbar
     * ("Imported N people, M instructions, K tags").
     */
    suspend fun importPlain(uri: Uri): Result<PlainImporter.ImportReport> =
        plainImporter.importFromUri(uri)

    /**
     * v1.8.0 (PROD-READINESS-P0-#1): trigger a one-shot
     * backup via WorkManager. The Settings sheet's "Back up
     * now" row calls this; the [BackupWorker] runs in the
     * background and writes the JSON snapshot to the app's
     * private filesDir under `backups/`.
     *
     * Returns immediately; the actual backup is async.
     */
    fun backupNow() {
        com.kaavalan.note.data.work.WorkManagerInitializer.enqueueBackupNow(appContext)
    }

    /**
     * v1.9.0 (PROD-READINESS-P3-P1-#3): the
     * one-shot update-check trigger. The
     * Settings sheet's "Check for updates" row
     * calls this; the latest result is exposed
     * via [updateCheckResult] as a [SharedFlow]
     * so the row can show a snackbar.
     *
     * The check is best-effort: a network
     * failure surfaces as
     * [UpdateInfo.Unavailable] with the reason.
     * The user can retry; we don't auto-retry.
     */
    private val _updateCheckResult = kotlinx.coroutines.flow.MutableSharedFlow<
        com.kaavalan.note.data.update.UpdateChecker.UpdateInfo>(extraBufferCapacity = 1)
    val updateCheckResult: kotlinx.coroutines.flow.SharedFlow<
        com.kaavalan.note.data.update.UpdateChecker.UpdateInfo> = _updateCheckResult

    private val _updateCheckInProgress = kotlinx.coroutines.flow.MutableStateFlow(false)
    val updateCheckInProgress: kotlinx.coroutines.flow.StateFlow<Boolean> = _updateCheckInProgress.asStateFlow()

    fun checkForUpdates() {
        if (_updateCheckInProgress.value) return
        _updateCheckInProgress.value = true
        viewModelScope.launch {
            runCatching { updateChecker.check() }
                .onSuccess { _updateCheckResult.tryEmit(it) }
                .onFailure { _updateCheckResult.tryEmit(
                    com.kaavalan.note.data.update.UpdateChecker.UpdateInfo.Unavailable(
                        it.message ?: it::class.java.simpleName,
                    ),
                ) }
            _updateCheckInProgress.value = false
        }
    }

    /**
     * v1.8.0 (PROD-READINESS-P2-#2): the number of
     * unresolved sync conflicts. The v1.5.0 vault-mode
     * build has no cloud sync, so the table is always
     * empty. The flow re-emits on every conflict insert
     * (the SyncEngine logs conflicts on LWW / version
     * mismatch during a cloud build). The Settings sheet
     * uses this to decide whether to show the "Sync
     * conflicts" row.
     */
    val syncConflictCount: StateFlow<Int> = syncConflictDao.observe()
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    /**
     * v1.8.0 (PROD-READINESS-P2-#2): the live list of
     * unresolved conflicts, ordered newest-first by
     * `detectedAt` DESC. The SyncConflictListScreen
     * observes this; tapping a row opens the diff
     * screen.
     */
    val syncConflicts: StateFlow<List<com.kaavalan.note.data.local.entities.SyncConflictEntity>> =
        syncConflictDao.observe()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    /**
     * v1.6.2: developer-only entry point. The
     * Settings sheet surfaces a "Load test data"
     * button (debug builds only) that delegates
     * to [FixtureLoader.loadFromAssets]. The
     * loader clears the existing mirror and
     * bulk-inserts the synthetic fixture so the UI
     * can be exercised with realistic data.
     */
    suspend fun loadFixture(): com.kaavalan.note.data.dev.FixtureLoader.LoadReport =
        fixtureLoader.loadFromAssets()

    /**
     * v1.6.4: clear-and-reload. The original
     * [loadFixture] already clears the mirror
     * before re-inserting (see
     * [FixtureLoader.loadFromAssets] — `if (it.isNotEmpty()) …`
     * is per-table), so this is functionally
     * identical. The Settings sheet exposes a
     * separate "Clear & reload" button so the
     * user can force a clean slate when the
     * persisted DB has stale data from an older
     * fixture version (v1.6.2 shipped a partial
     * load that left K. Suresh with 1 OPEN and
     * everyone else with 0).
     */
    suspend fun clearAndReloadFixture(): com.kaavalan.note.data.dev.FixtureLoader.LoadReport =
        fixtureLoader.loadFromAssets()



    /**


     * Tier 0.6: compute the on-disk size of the app's


     * local data. The calculation sums:


     *


     *  - the SQLCipher Room DB file (`kaavalan-note.db`)


     *  - the WAL (`kaavalan-note.db-wal`) and SHM (`kaavalan-note.db-shm`)


     *    companions; the WAL is often the bulk of the size


     *    on a write-heavy vault


     *  - the `filesDir/captures/` directory of photo


     *    captures (M2-T2's ML Kit OCR pipeline writes the


     *    JPEG here)


     *


     * Runs on [Dispatchers.IO] (the call site maps the storage


     * flow onto IO via [kotlinx.coroutines.flow.flowOn]). The


     * function is `private` to the VM because the file layout


     * is an implementation detail. Tests for the size


     * computation live in


     * [com.kaavalan.note.ui.settings.StorageSizeTest] and call an


     * equivalent helper directly via a Robolectric context.


     */


    private suspend fun computeStorageSizeBytes(): Long = withContext(Dispatchers.IO) {


        val db = appContext.getDatabasePath(AppDatabase.NAME)


        val wal = File(db.path + "-wal")


        val shm = File(db.path + "-shm")


        val captures = File(appContext.filesDir, "captures")


        var total = 0L


        listOf(db, wal, shm).forEach { f ->


            if (f.exists()) total += f.length()


        }


        if (captures.isDirectory) {


            captures.listFiles()?.forEach { f ->


                if (f.isFile) total += f.length()


            }


        }


        total


    }



    private fun isValidPin(pin: String): Boolean {


        if (pin.length !in 4..6) return false


        return pin.all { it.isDigit() }


    }


}





/**


 * v1.5.3 (VAULT-008): the storage card payload. Counts only —


 * no per-row detail on this card (the home tab is the right


 * place to see individual rows).


 *


 * Tier 0.6: also carries the on-disk size in bytes (DB file


 * + WAL + SHM + photos dir). The Settings row formats this


 * as "X.X MB on this phone". The field is `0L` on a fresh


 * install before the user has added any data.


 */


data class StorageInfo(


    val peopleCount: Int = 0,


    val instructionCount: Int = 0,


    val tagCount: Int = 0,


    val sizeBytes: Long = 0L,


)









/**


 * v1.5.3 (VAULT-008): the version card payload. Read once at


 * VM construction from BuildConfig (the value doesn't change


 * at runtime, so a one-shot read is fine).


 */


data class AppVersion(


    val name: String = "",


    val code: Int = 0,


)

