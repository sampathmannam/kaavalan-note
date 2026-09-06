package com.kaavalan.note

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.kaavalan.note.data.local.AppInitializer
import com.kaavalan.note.data.work.WorkManagerInitializer
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class KaavalanApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    /**
     * M3-T1: one-shot startup tasks. Injected by Hilt's `@HiltAndroidApp`
     * path. Runs in [onCreate] before any other Hilt-injected component
     * is touched, so by the time [com.kaavalan.note.di.DatabaseModule] is
     * asked for the [com.kaavalan.note.data.local.AppDatabase] the
     * M2 plain DB is already wiped and the SQLCipher passphrase is
     * generated.
     */
    @Inject lateinit var appInitializer: AppInitializer

    /**
     * v1.8.0 (PROD-READINESS-P2-#3): the user bootstrap.
     * Injected by Hilt so the device-owner row is in
     * place before any UI code reads the [UserDao].
     */
    @Inject lateinit var userBootstrap: com.kaavalan.note.data.user.UserBootstrap

    // v2.0.2 (PM rating): the DB preflight. Runs a
    // `SELECT 1` on first launch and sets the
    // "database corrupt" flag in [SecurePreferences]
    // if the open throws. The Settings sheet reads
    // the flag and surfaces a "Database error — tap
    // to erase and start fresh" banner. The preflight
    // is async so a slow DB open doesn't block the
    // launcher activity.
    @Inject lateinit var databasePreflight: com.kaavalan.note.data.local.DatabasePreflight

    // v2.1.1 (security): the Google OAuth client.
    // Injected so the cold-start path can check
    // [GoogleOAuthClient.isSignedIn] and only
    // schedule the daily Drive backup when the
    // user has signed in. The v2.1.0 path scheduled
    // it unconditionally, which meant the worker
    // fired on every cold start of a device that
    // had never signed in and dumped a failure
    // result to the WorkManager log.
    @Inject lateinit var googleOAuthClient: com.kaavalan.note.data.backup.GoogleOAuthClient

    // v2.1.1 (security): the encrypted-preferences
    // store. The cold-start path reads
    // [SecurePreferences.getBackupEncryptionKeyHash]
    // so the daily Drive backup is only scheduled
    // when the user has set a passphrase.
    @Inject lateinit var securePreferences: com.kaavalan.note.data.auth.SecurePreferences

    /**
     * v2.1.2 (startup): the application-scoped coroutine scope
     * (`SupervisorJob + Dispatchers.Default`, see
     * [com.kaavalan.note.di.CoroutineModule]). Replaces the
     * `GlobalScope` the v2.1.1 cold-start path used.
     */
    @Inject @com.kaavalan.note.di.ApplicationScope
    lateinit var applicationScope: kotlinx.coroutines.CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // v1.9.0 (PROD-READINESS-P3-P1-#1): install
        // the uncaught-exception handler BEFORE
        // anything else runs. The handler writes
        // a structured crash log to
        // `cacheDir/crashes/`; the user can share
        // it with support on the next launch.
        // Installed as the SECOND handler (the
        // system default is the first; this wraps
        // it so the user still gets the standard
        // "App has stopped" dialog).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            com.kaavalan.note.ui.util.CrashLog.write(this, throwable)
            previous?.uncaughtException(thread, throwable)
        }
        appInitializer.runOnAppStart()
        // v2.1.2 (startup): the device-owner bootstrap and the
        // database preflight, in that order, entirely off the main
        // thread.
        //
        // v2.1.1 ran the bootstrap in a `runBlocking { withTimeout(2s)
        // { withContext(IO) { ... } } }` on the main thread. The
        // ordering it bought was real — `DatabasePreflight`'s step-2
        // check needs the device-owner row to already exist, and it
        // was launched separately — but the price was up to two
        // seconds of blocked main thread on every cold start, before
        // the launcher activity could draw. First launch is the worst
        // case, because that is when SQLCipher passphrase generation
        // (Argon2id + AES) actually runs, and it stacks with the rest
        // of `onCreate`. That is ANR territory and it shows up in Play
        // vitals as slow cold start.
        //
        // Sequencing the two inside one coroutine gives the same
        // ordering guarantee — `runPreflight()` cannot start until
        // `ensureDeviceOwner()` has returned — with no main-thread
        // block and no arbitrary timeout. The 2 s ceiling existed only
        // to bound the blocking, so it goes with it: the bootstrap now
        // takes as long as it takes, off the critical path.
        //
        // The injected @ApplicationScope replaces GlobalScope: same
        // application lifetime, but a real SupervisorJob, so a failure
        // in one child cannot cancel the other and the scope is
        // substitutable in tests.
        applicationScope.launch(Dispatchers.IO) {
            runCatching { userBootstrap.ensureDeviceOwner() }
                .onFailure {
                    android.util.Log.w("KaavalanApplication", "ensureDeviceOwner bootstrap failed", it)
                }
            // v2.0.2 (PM rating): the database preflight. Runs a
            // `SELECT 1` and sets the "database corrupt" flag in
            // SecurePreferences if the open throws; the Settings sheet
            // reads the flag and surfaces the "Database error — tap to
            // erase and start fresh" banner. Failures are logged, not
            // swallowed, so a real DB error reaches logcat instead of
            // silently flipping the flag to false.
            runCatching { databasePreflight.runPreflight() }
                .onFailure {
                    android.util.Log.e("KaavalanApplication", "database preflight failed", it)
                }
        }
        // v1.5.0 vault mode: no cloud sync. The
        // [com.kaavalan.note.data.work.WorkManagerInitializer] periodic
        // drain + capture-sync schedules are intentionally NOT
        // called. The per-write `enqueueCaptureSync` in
        // [com.kaavalan.note.data.captures.RoomCaptureRepository] still
        // fires one-shot workers (a no-op without Supabase creds);
        // the periodic schedule was the one that mattered for the
        // "I was offline and now I'm not" self-heal, and v1.5.0
        // has no offline-to-online path because there is no online.
        // The code paths are left in place so a future Settings
        // toggle can re-enable cloud sync without a refactor.
        //
        // v1.8.0 (PROD-READINESS-P0-#1): the daily local
        // backup IS scheduled. The backup is local (writes to
        // filesDir, no network), so the v1.5.0 vault-mode
        // "no cloud = no WorkManager" rule doesn't apply.
        // WorkManagerInitializer.scheduleBackup is idempotent
        // (KEEP policy) so calling it on every cold start is
        // a no-op after the first.
        com.kaavalan.note.data.work.WorkManagerInitializer.scheduleBackup(this)
        // v1.8.0 (PROD-READINESS-P2-#5): the daily
        // retention sweep is also scheduled. Same
        // KEEP-on-re-enqueue idempotency as the backup.
        com.kaavalan.note.data.work.WorkManagerInitializer.scheduleRetention(this)
        // v2.1.1 (security): only schedule the daily
        // Google Drive backup when the user has both
        // (a) signed in to Google and (b) set a backup
        // passphrase. v2.1.0 scheduled unconditionally,
        // which meant the worker fired on every cold
        // start of a device that had never signed in
        // and dumped a failure result to the
        // WorkManager log. The user re-enables the
        // schedule the next time they sign in.
        // v2.1.2 (startup): both predicates read
        // EncryptedSharedPreferences, which means an AndroidKeyStore
        // unwrap plus disk I/O — on the main thread in v2.1.1, inside
        // Application.onCreate. Moved to the IO dispatcher. Scheduling
        // is idempotent (KEEP policy) and nothing on the launch path
        // depends on it having happened, so deferring it by a few
        // milliseconds is safe.
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                if (googleOAuthClient.isSignedIn() &&
                    securePreferences.getBackupEncryptionKeyHash() != null
                ) {
                    com.kaavalan.note.data.work.WorkManagerInitializer.scheduleDriveBackup(this@KaavalanApplication)
                }
            }.onFailure {
                android.util.Log.w("KaavalanApplication", "Drive backup scheduling check failed", it)
            }
        }
    }

    /**
     * M3-T2: WorkManager on-demand init. We declare the
     * [Configuration.Provider] interface so Hilt can inject the
     * [HiltWorkerFactory]; the actual [WorkManager.getInstance] call
     * is deferred to first use (see the M3 manifest change that
     * disables the auto-init ContentProvider).
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
