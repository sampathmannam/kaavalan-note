package com.kaavalan.note.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kaavalan.note.data.auth.SecurePreferences
import com.kaavalan.note.data.local.AppDao
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.AuditChainEventDao
import com.kaavalan.note.data.local.CaptureDao
import com.kaavalan.note.data.local.ImportantDateDao
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.InstructionFtsDao
import com.kaavalan.note.data.local.InstructionTagDao
import com.kaavalan.note.data.local.NudgeDraftDao
import com.kaavalan.note.data.local.PersonDao
import com.kaavalan.note.data.local.PersonLinkDao
import com.kaavalan.note.data.local.SyncConflictDao
import com.kaavalan.note.data.local.SyncQueueDao
import com.kaavalan.note.data.local.TagDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.kaavalan.note.data.local.SqlCipherMemorySecurityHook
import javax.inject.Singleton

/**
 * Hilt providers for the local Room database. M2-T6 + M3-T1.
 *
 * **Encryption (M3-T1).** The DB is now opened through SQLCipher
 * with a 32-byte passphrase generated on first launch by
 * [SecurePreferences.databasePassphrase] and persisted in
 * EncryptedSharedPreferences. The on-disk `kaavalan-note.db` file is
 * unreadable without that passphrase. The M2 unencrypted DB is
 * wiped on the M2 -> M3 transition (handled by
 * [com.kaavalan.note.data.local.AppInitializer] on first run).
 *
 * **Sign-out (M3-T4).** When the user signs out,
 * [SecurePreferences.clearDatabasePassphrase] deletes the key. The
 * next DB read fails (SQLCipher: "file is not a database"); the
 * AppInitializer then deletes the file and opens a fresh DB. On
 * the next sign-in the user pulls from Supabase.
 *
 * **Migration.** The M2 -> M3 schema bump (no schema changes, just
 * `version = 3` to trigger the AppInitializer wipe) is intentional.
 * The old plain DB is destroyed; the new encrypted DB starts empty
 * and is filled from Supabase on the first
 * [com.kaavalan.note.data.local.RoomPersonRepository.refreshFromNetwork]
 * call after sign-in.
 *
 * **v1.2.1 (BUG-DATA-009) PRAGMA foreign_keys = ON.** SQLite's
 * `PRAGMA foreign_keys` is OFF by default for every connection
 * (it's a runtime, per-connection setting, not a schema flag).
 * Without it, `ON DELETE CASCADE` is silently ignored and a deleted
 * parent leaves orphan rows. We enable it on every `onOpen` via a
 * [RoomDatabase.Callback]. This is a SQLite-level best practice
 * that Room doesn't enable on its own; without it, the v1.1 cascade
 * delete on `instruction_tags` (when an instruction is deleted)
 * doesn't fire.
 *
 * **v1.4.3 (F-37) PRAGMA cipher_memory_security = OFF.** SQLCipher
 * defaults to `cipher_memory_security = ON`, which calls `mlock()`
 * on the database file to prevent it being paged to disk. Android
 * restricts `mlock` (returns `ENOMEM=12`), so every DB open logs
 * 8+ "mlock() returned -1 errno=12" warnings. We turn the mlock
 * off — encryption is unchanged, only the (unachievable on Android)
 * mlock-guarantee is dropped. The passphrase is still in
 * EncryptedSharedPreferences (Keystore-backed), so this is the
 * correct trade-off. See
 * https://www.zetetic.net/sqlcipher/sqlcipher-api/#cipher_memory_security
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        securePreferences: SecurePreferences,
    ): AppDatabase {
        val passphrase = securePreferences.databasePassphrase()
        // v1.9.11 (Obs-3): the [SqlCipherMemorySecurityHook]
        // sets `PRAGMA cipher_memory_security = OFF` in
        // [SQLiteDatabaseHook.preKey], which fires BEFORE
        // the keying phase. The v1.4.3 on-open fix (see
        // [onOpenPragmaCallback]) set the same pragma but
        // too late — by the time `onOpen` runs, the
        // keying-phase mlock attempts have already fired
        // (and failed with ENOMEM=12). This hook closes
        // the gap. The 3-arg constructor accepts the hook.
        val factory = SupportOpenHelperFactory(
            passphrase,
            SqlCipherMemorySecurityHook(),
            false, // enableWriteAheadLogging = false (existing config)
        )
        // Zero the passphrase bytes after handing them to SQLCipher.
        // SQLCipher has already copied what it needs internally.
        passphrase.fill(0)
        return Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .openHelperFactory(factory)
            .addCallback(onOpenPragmaCallback())
            // v1.2.2 (BUG-DATA-001): the first non-destructive
            // migration in the project. v8 -> v9 adds
            // `nextAttemptAt` to sync_queue. All previous bumps
            // used `fallbackToDestructiveMigration`, which would
            // have nuked any PENDING outbox rows on the upgrade.
            // This migration preserves them — `ALTER TABLE ADD
            // COLUMN` is a fast, online, lossless operation in
            // SQLite (the file is rewritten but no data is read
            // or copied; existing rows get the DEFAULT value).
            //
            // Note: v8 is NOT in the fallback list below — Room
            // throws IllegalArgumentException if a migration's
            // start version (8) is also in fallbackToDestructive.
            // v2 -> v7 still fall back to destructive (the pre-M3
            // versions had no outbox in production use, so there
            // was nothing to preserve).
            .addMigrations(
                // v2.1.0 (PM rating): the v2-v7 → v8 best-effort
                // migrations. See the MIGRATION_2_3..MIGRATION_7_8
                // definitions in AppDatabase.kt for the full
                // rationale. The honest answer: I do not have
                // the exact v3-v7 schema, so the migrations
                // are defensive (try-catch around each step).
                // A pre-v8 user whose v15-schema mismatches the
                // migration result will see a Room
                // `IllegalStateException` and the
                // `DatabasePreflight` will surface a "Database
                // error" banner. That's no worse than the
                // v2.0.x `fallbackToDestructiveMigrationFrom`
                // (which silently wiped the data).
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17,
            )
            .build()
    }

    /**
     * v1.2.2 migration: add `nextAttemptAt` to sync_queue for
     * exponential-backoff drain. `INTEGER NOT NULL DEFAULT 0`
     * — existing rows get 0 = "ready now" so they're tried
     * immediately on the first v1.2.2 drain.
     */
    private val MIGRATION_8_9: Migration = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sync_queue ADD COLUMN nextAttemptAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_queue_nextAttemptAt ON sync_queue(nextAttemptAt)")
        }
    }

    /**
     * Per-connection `PRAGMA`s run on every Room open. Exposed as
     * a separate factory so the unit test can verify the callback
     * without standing up the full SQLCipher-backed [AppDatabase]
     * (which fails in Robolectric because the native lib isn't
     * packaged there).
     *
     * **v1.2.1 (BUG-DATA-009): `PRAGMA foreign_keys = ON`.** SQLite
     * default is OFF per-connection; without it, `ON DELETE CASCADE`
     * is silently ignored and a deleted parent leaves orphan rows.
     *
     * **v1.4.3 (F-37): `PRAGMA cipher_memory_security = OFF`.**
     * SQLCipher's default tries to `mlock()` the database file in
     * memory. Android restricts `mlock` (returns `ENOMEM=12`), so
     * SQLCipher logs 8+ mlock warnings on every DB open. We turn
     * the mlock off — encryption is unchanged, only the (unachievable
     * on Android) mlock-guarantee is dropped. See
     * https://www.zetetic.net/sqlcipher/sqlcipher-api/#cipher_memory_security
     *
     * **v1.9.10 (Obs-3) honest note:** the v1.9.8 audit's refuter
     * surfaced 31 `sqlcipher: ERROR MEMORY sqlcipher_mlock: mlock()
     * returned -1 errno=12` lines in the v1.9.9 cold-start logcat.
     * Those warnings fire during the SQLCipher key derivation —
     * which happens BEFORE [onOpen] runs. The pragma above is set
     * on every connection open, but the keying (and the mlock
     * attempt) is part of `SupportOpenHelperFactory(passphrase)`
     * and is not exposed to a Room callback. To kill the warnings
     * entirely we'd need a custom openHelper that sets the pragma
     * before keying, which is a SQLCipher-Android library
     * limitation, not a Kaavalan note bug. The pragma is still correct
     * (no later mlock attempts are made on the same connection
     * after [onOpen] sets the flag), and the encryption is
     * unchanged. The remaining warnings are a known cosmetic
     * issue — flagged in the v1.9.10 release notes' deferrals.
     */
    fun onOpenPragmaCallback(): RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            db.execSQL("PRAGMA foreign_keys = ON")
            // v1.4.3 (F-37): see class-level KDoc. The passphrase
            // is still in EncryptedSharedPreferences (Keystore-backed)
            // so dropping the (Android-unachievable) mlock is safe.
            db.execSQL("PRAGMA cipher_memory_security = OFF")
        }
    }

    @Provides
    fun providePersonDao(db: AppDatabase): PersonDao = db.personDao()

    @Provides
    fun provideInstructionDao(db: AppDatabase): InstructionDao = db.instructionDao()

    @Provides
    fun provideCaptureDao(db: AppDatabase): CaptureDao = db.captureDao()

    @Provides
    fun provideSyncQueueDao(db: AppDatabase): SyncQueueDao = db.syncQueueDao()

    @Provides
    fun provideSyncConflictDao(db: AppDatabase): SyncConflictDao = db.syncConflictDao()

    @Provides
    fun provideTagDao(db: AppDatabase): TagDao = db.tagDao()

    @Provides
    fun provideInstructionTagDao(db: AppDatabase): InstructionTagDao = db.instructionTagDao()

    @Provides
    fun provideAppDao(db: AppDatabase): AppDao = db.appDao()

    @Provides
    fun provideNudgeDraftDao(db: AppDatabase): NudgeDraftDao = db.nudgeDraftDao()

    @Provides
    fun provideInstructionFtsDao(db: AppDatabase): InstructionFtsDao = db.instructionFtsDao()
    // v2.0 Tier 2 (§2.5, §2.12): new DAOs for the new tables
    // in the v10 -> v11 migration.
    @Provides
    fun provideImportantDateDao(db: AppDatabase): ImportantDateDao = db.importantDateDao()

    @Provides
    fun providePersonLinkDao(db: AppDatabase): PersonLinkDao = db.personLinkDao()

    // v1.8.0 (PROD-READINESS-P2-#4): the audit-chain DAO.
    @Provides
    fun provideAuditChainEventDao(db: AppDatabase): AuditChainEventDao = db.auditChainEventDao()

    // v1.8.0 (PROD-READINESS-P2-#3): the user DAO.
    @Provides
    fun provideUserDao(db: AppDatabase): com.kaavalan.note.data.user.UserDao = db.userDao()

    // v2.0 (Hierarchy): the delivery receipt DAO.
    @Provides
    fun provideDeliveryReceiptDao(db: AppDatabase): com.kaavalan.note.data.local.DeliveryReceiptDao =
        db.deliveryReceiptDao()
}
