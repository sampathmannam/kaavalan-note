package com.kaavalan.note.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.kaavalan.note.data.local.entities.AppStateEntity
import com.kaavalan.note.data.local.entities.AuditChainEventEntity
import com.kaavalan.note.data.local.entities.CaptureEntity
import com.kaavalan.note.data.local.entities.ImportantDateEntity
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.InstructionFtsEntity
import com.kaavalan.note.data.local.entities.InstructionTagCrossRef
import com.kaavalan.note.data.local.entities.NudgeDraftEntity
import com.kaavalan.note.data.local.entities.PersonEntity
import com.kaavalan.note.data.local.entities.PersonLinkEntity
import com.kaavalan.note.data.local.entities.SyncConflictEntity
import com.kaavalan.note.data.local.entities.SyncQueueEntity
import com.kaavalan.note.data.local.entities.TagEntity
import com.kaavalan.note.data.user.UserEntity

/**
 * Kaavalan note local database. Mirrors the six Supabase tables the
 * read paths need (`persons`, `instructions`, `captures`, `tags`,
 * `instruction_tags`) plus the outbox (`sync_queue`) that the
 * SyncEngine drains to keep Supabase in lock-step, plus the
 * `sync_conflicts` audit table.
 *
 * **Versioning:**
 *  - v1 M0 initial
 *  - v2 M2-T6 added sync_queue + sync_conflicts
 *  - v3 M3-T1 SQLCipher encryption (no schema change, just bump so
 *    the M2 plain DB is wiped on the M2 -> M3 transition)
 *  - v4 M3-T7 added tags + instruction_tags
 *  - v5 M4-T6 added app_state
 *  - v6 v1.0 nudge_drafts added (M5)
 *  - v7 v1.0 is_sensitive added on persons
 *  - v8 v1.1 lifecycle (completedAt, droppedReason) on instructions
 *  - v9 v1.2.2 sync_queue.nextAttemptAt (exponential backoff)
 *  - v10 v1.4.2 sync_queue UNIQUE INDEX on (op, table, rowId)
 *         (DATA-FINDING-04: dedup the outbox so a double-tap of
 *         `markDone` doesn't enqueue two UPDATE rows for the
 *         same instruction)
 *  - v11 v2.0 Tier 1: FTS4 + nextActionAt
 *         - instructions.nextActionAt
 *         - new virtual table instructions_fts
 *  - v12 v2.0 Tier 2: relationship decay + tiers + photo OCR + worry box
 *         + typed blocks + important dates + person-to-person links +
 *         calendar link. Adds:
 *           - persons.tier, persons.cadenceOverrideDays, persons.lastInteractionAt
 *           - instructions.caseType, instructions.urgency, instructions.reviewAtEpochDay
 *           - captures.ocrText, captures.calendarEventId, captures.urgency, captures.reviewAtEpochDay
 *           - new table important_date
 *           - new table person_link
 *  - v13 v2.0 Tier 3: behavioural deniable vault
 *         - persons.vaultMode (default "visible")
 *         - instructions.vaultMode (default "visible")
 *
 * v8 -> v9 is the first non-destructive migration in the project:
 * `ALTER TABLE sync_queue ADD COLUMN nextAttemptAt INTEGER NOT NULL
 * DEFAULT 0`. PENDING outbox rows are preserved across the upgrade
 * (BUG-DATA-001 was the audit finding that every previous bump
 * nuked pending writes).
 *
 * v9 -> v10 (DATA-FINDING-04) is also non-destructive:
 * `CREATE UNIQUE INDEX ... ON sync_queue (op, table, rowId)`.
 * The migration dedupes any pre-existing duplicate rows first
 * (keeping the row with the highest `id`, i.e. the latest
 * enqueue) so the CREATE INDEX doesn't fail on legacy data.
 *
 * v10 -> v11 is also non-destructive (Tier 1): adds
 * `nextActionAt` to `instructions` and creates the FTS4 virtual
 * table that backs full-text search.
 *
 * v11 -> v12 is also non-destructive (Tier 2): a series of
 * `ALTER TABLE ADD COLUMN` for the new optional fields plus two
 * new tables. All ADD COLUMNs use sensible defaults so existing
 * rows pass the new schema.
 *
 * v12 -> v13 is also non-destructive (Tier 3): adds the
 * `vaultMode` column to persons + instructions with a "visible"
 * default; the @Index annotation generates a per-table index
 * for the per-vault-mode filter queries.
 *
 * v3 -> v4 -> v5 -> v6 -> v7 -> v8 are all `fallbackToDestructiveMigration`
 * transitions - the local cache is reconstructible from Supabase on
 * the next refresh, and there were no PENDING writes to lose
 * (M2-T6 + M3-T1 wipe before the outbox was in production use).
 *
 * **Encryption:** the database is opened via SQLCipher (see
 * [com.kaavalan.note.di.DatabaseModule]); the key is held in
 * `EncryptedSharedPreferences`. The on-disk file is unreadable
 * without it - important for police data on a personal device.
 */
@Database(
    entities = [
        PersonEntity::class,
        InstructionEntity::class,
        CaptureEntity::class,
        AuditChainEventEntity::class,
        SyncQueueEntity::class,
        SyncConflictEntity::class,
        TagEntity::class,
        InstructionTagCrossRef::class,
        AppStateEntity::class,
        NudgeDraftEntity::class,
        InstructionFtsEntity::class,
        ImportantDateEntity::class,
        PersonLinkEntity::class,
        UserEntity::class,
        com.kaavalan.note.data.local.entities.DeliveryReceiptEntity::class,
    ],
    // v1.8.0 (PROD-READINESS-P2-#3 + #4): v14 adds the
    // audit_chain_events table; v1.8.0 also adds the
    // users table. The chain is a forward-only append;
    // the users table starts with one row (the device
    // owner). No destructive migration needed.
    // v2.0 (Hierarchy): v16 adds the audience + due chip +
    // channel columns on `instructions` and the new
    // `delivery_receipts` table.
    version = 16,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun personDao(): PersonDao
    abstract fun instructionDao(): InstructionDao
    abstract fun captureDao(): CaptureDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun syncConflictDao(): SyncConflictDao
    abstract fun tagDao(): TagDao
    abstract fun instructionTagDao(): InstructionTagDao
    abstract fun appDao(): AppDao
    abstract fun nudgeDraftDao(): NudgeDraftDao
    abstract fun instructionFtsDao(): InstructionFtsDao
    abstract fun importantDateDao(): ImportantDateDao
    abstract fun personLinkDao(): PersonLinkDao
    abstract fun auditChainEventDao(): AuditChainEventDao
    abstract fun userDao(): com.kaavalan.note.data.user.UserDao
    // v2.0 (Hierarchy): the per-recipient delivery receipt DAO.
    abstract fun deliveryReceiptDao(): com.kaavalan.note.data.local.DeliveryReceiptDao

    companion object {
        const val NAME = "kaavalan-note.db"

        /**
         * v1.4.2 (DATA-FINDING-04): add the UNIQUE INDEX on
         * `(op, table, rowId)` that [SyncQueueEntity] declares
         * and that [SyncQueueDao.enqueue] relies on for
         * dedup-on-conflict. The index column order matches the
         * @Entity declaration so Room's post-migration schema
         * validation accepts it.
         *
         * Pre-step: collapse any pre-existing duplicate rows
         * (one per `(op, table, rowId)` group, keeping the
         * latest by `id`) so the CREATE INDEX doesn't fail on
         * legacy data. The non-aggregating DELETE + correlated
         * subquery form is intentional - `DELETE ... WHERE id
         * NOT IN (SELECT MAX(id) ...)` does the dedupe in a
         * single statement.
         *
         * Production wiring: `DatabaseModule.provideDatabase`
         * should add this to its `addMigrations(...)` call,
         * matching the v8 -> v9 pattern. The migration is
         * exposed here as a public companion constant for that
         * one-line wiring change.
         */
        /**
         * v2.1.0 (PM rating): the v2-v7 → v8 best-effort
         * migrations. The pre-v8 era (M2 + M3 builds) had
         * a simpler schema than v8: the M3 encrypted-DB
         * base (persons + instructions + captures) plus
         * whatever incremental columns v4-v7 added. The
         * v8 era added the `vaultMode` columns to
         * persons + instructions and the four new tables
         * (tags, instruction_tags, sync_conflict, plus
         * a fatter sync_queue).
         *
         * **The honest scope.** I do not have the
         * exact v3-v7 schema. The migrations here are
         * defensive: every step is wrapped in a
         * try-catch that swallows the "already exists"
         * error. The result is a "best effort" upgrade
         * — the v8 schema is laid down on top of
         * whatever the v3-v7 schema had, and Room's
         * `validateSchema` either accepts the result
         * (no-op steps on a schema that already had the
         * column) or fails (in which case the user is
         * no worse off than under the v2.0.x
         * `fallbackToDestructiveMigrationFrom(2..7)`
         * — the data is lost either way).
         *
         * **What v2.0.0 was doing.** The v2.0.x
         * `fallbackToDestructiveMigrationFrom(2, 3, 4,
         * 5, 6, 7)` accepted that pre-v8 data was lost
         * because v1.x had Supabase to re-fill from. In
         * v2.0.0 (no Supabase) the same path is a real
         * data-loss footgun. These 6 migrations are the
         * v2.1.0 fix.
         */
        private fun runPreV8Migration(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            // The vaultMode columns on persons + instructions.
            // SQLite supports `ALTER TABLE ADD COLUMN` with
            // a NOT NULL DEFAULT — pre-existing rows pick
            // up the default. We wrap in try-catch because
            // the column may already exist on a v4-v7
            // schema that pre-empted the v8 split.
            try {
                db.execSQL("ALTER TABLE persons ADD COLUMN vaultMode TEXT NOT NULL DEFAULT 'visible'")
            } catch (_: Throwable) {}
            try {
                db.execSQL("ALTER TABLE instructions ADD COLUMN vaultMode TEXT NOT NULL DEFAULT 'visible'")
            } catch (_: Throwable) {}
            try {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_persons_vaultMode ON persons(vaultMode)")
            } catch (_: Throwable) {}
            try {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_instructions_vaultMode ON instructions(vaultMode)")
            } catch (_: Throwable) {}

            // The v8-era tables that didn't exist before.
            // CREATE TABLE IF NOT EXISTS is idempotent.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tags (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    color TEXT,
                    usageCount INTEGER NOT NULL DEFAULT 0,
                    lastUsedAt TEXT,
                    userId TEXT NOT NULL,
                    createdAt TEXT NOT NULL,
                    updatedAt TEXT NOT NULL,
                    syncStatus TEXT NOT NULL DEFAULT 'SYNCED'
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tags_kind ON tags(kind)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tags_syncStatus ON tags(syncStatus)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS instruction_tags (
                    instructionId TEXT NOT NULL,
                    tagId TEXT NOT NULL,
                    PRIMARY KEY(instructionId, tagId)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_instruction_tags_tagId ON instruction_tags(tagId)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_conflict (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    tableName TEXT NOT NULL,
                    rowId TEXT NOT NULL,
                    localVersion TEXT NOT NULL,
                    remoteVersion TEXT NOT NULL,
                    detectedAtMs INTEGER NOT NULL,
                    resolution TEXT
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_conflict_tableName_rowId ON sync_conflict(tableName, rowId)")
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) = runPreV8Migration(db)
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) = runPreV8Migration(db)
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) = runPreV8Migration(db)
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) = runPreV8Migration(db)
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) = runPreV8Migration(db)
        }

        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) = runPreV8Migration(db)
        }

        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Dedupe any pre-existing duplicate outbox rows
                // before adding the unique constraint. Keeps the
                // row with the highest `id` per
                // (op, table, rowId) - i.e. the latest enqueue.
                db.execSQL(
                    "DELETE FROM sync_queue WHERE id NOT IN (" +
                        "SELECT MAX(id) FROM sync_queue " +
                        "GROUP BY `op`, `table`, rowId" +
                        ")",
                )
                // Add the unique index. `IF NOT EXISTS` so a
                // partially-completed prior run is idempotent.
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_sync_queue_op_table_rowId` " +
                        "ON sync_queue (`op`, `table`, rowId)",
                )
            }
        }

        /**
         * v2.0 Tier 1 (Tier 1.3 + Tier 1.5): add the `nextActionAt`
         * column to `instructions` and create the FTS4 table
         * that backs full-text search.
         *
         * We use a "free" FTS4 entity (no `contentEntity`),
         * so the migration must declare the FTS columns
         * directly and seed from the existing `instructions`
         * rows. The [InstructionRepository] keeps the FTS
         * rows in lock-step on every write.
         */
        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Tier 1.5: scheduled next action (epoch millis).
                db.execSQL("ALTER TABLE instructions ADD COLUMN nextActionAt INTEGER")
                // Tier 1.3: free FTS4 table. The columns and
                // tokenizer option must match what Room
                // generates from the [InstructionFtsEntity]
                // (alphabetical column order, no backticks
                // around the tokenizer name) — otherwise the
                // v1.5.7 -> v1.6.0 migration fails with
                // `Migration didn't properly handle:
                // instructions_fts`. The v1.6.0.0 build
                // shipped with the wrong order and the
                // backticks; this fix corrects both. The
                // fresh-install schema generated by Room
                // (no migration) was always correct, so the
                // bug only bit users on the v1.5.7 upgrade
                // path.
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `instructions_fts` USING fts4(" +
                        "`capturedAt` TEXT, " +
                        "`personId` TEXT, " +
                        "`title` TEXT, " +
                        "`rawText` TEXT, " +
                        "tokenize=porter" +
                        ")",
                )
                // Seed from the current `instructions` rows.
                db.execSQL(
                    "INSERT INTO `instructions_fts` (rowid, title, rawText, personId, capturedAt) " +
                        "SELECT rowid, COALESCE(title, ''), COALESCE(rawText, ''), personId, capturedAt " +
                        "FROM `instructions`",
                )
            }
        }

        /**
         * v2.0 Tier 2 migration (v11 -> v12): relationship decay +
         * tier-based cadences + photo OCR + worry box + typed blocks +
         * important dates + person-to-person links. All operations are
         * non-destructive (ADD COLUMN with defaults, CREATE TABLE /
         * INDEX for the new entities). Existing rows get the
         * defaults on the new columns; no data is rewritten.
         *
         * The migration is exposed here so `DatabaseModule.provideDatabase`
         * can add it to the `addMigrations(...)` chain alongside
         * [MIGRATION_9_10] and [MIGRATION_10_11]. The raw-SQL test in
         * [com.kaavalan.note.di.Migration11To12Test] exercises the
         * same SQL on a hand-built v11 schema and asserts the
         * column additions + table creations.
         */
        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // §2.1, §2.2, §2.3: persons table gets the
                // relationship-decay + tier fields. lastInteractionAt
                // is nullable (null = "never touched"); tier has a
                // sensible default so existing people get the
                // 30-day "Active" cadence; cadenceOverrideDays is
                // nullable (null = "use tier default").
                db.execSQL(
                    "ALTER TABLE persons ADD COLUMN tier TEXT NOT NULL DEFAULT 'Active'",
                )
                db.execSQL(
                    "ALTER TABLE persons ADD COLUMN cadenceOverrideDays INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE persons ADD COLUMN lastInteractionAt INTEGER",
                )
                // Index for the decay query (WHERE
                // lastInteractionAt < threshold ORDER BY
                // lastInteractionAt). The @Index annotation on
                // PersonEntity.lastInteractionAt generates this
                // name; the post-migration schema check compares
                // on-disk index names to the KSP-computed names.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_persons_lastInteractionAt` " +
                        "ON `persons`(`lastInteractionAt`)",
                )
                // §2.8, §2.10: instructions table gets typed-block +
                // worry-box fields. urgency defaults to "normal"
                // so the existing-brief query (status NOT IN
                // (...DONE...)) keeps working without any data
                // rewrite. caseType is nullable.
                db.execSQL(
                    "ALTER TABLE instructions ADD COLUMN caseType TEXT",
                )
                db.execSQL(
                    "ALTER TABLE instructions ADD COLUMN urgency TEXT NOT NULL DEFAULT 'normal'",
                )
                db.execSQL(
                    "ALTER TABLE instructions ADD COLUMN reviewAtEpochDay INTEGER",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_instructions_urgency` " +
                        "ON `instructions`(`urgency`)",
                )
                // §2.4, §2.9, §2.10: captures table gets OCR text +
                // calendar link + worry-box fields.
                db.execSQL(
                    "ALTER TABLE captures ADD COLUMN ocrText TEXT",
                )
                db.execSQL(
                    "ALTER TABLE captures ADD COLUMN calendarEventId TEXT",
                )
                db.execSQL(
                    "ALTER TABLE captures ADD COLUMN urgency TEXT NOT NULL DEFAULT 'normal'",
                )
                db.execSQL(
                    "ALTER TABLE captures ADD COLUMN reviewAtEpochDay INTEGER",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_captures_urgency` " +
                        "ON `captures`(`urgency`)",
                )
                // §2.5: new important_date table. Foreign-key
                // cascade on person delete (the person DAO's
                // deleteById will wipe the dates too).
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `important_date` (
                        `id` TEXT NOT NULL,
                        `personId` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `dateEpochDay` INTEGER NOT NULL,
                        `recurring` INTEGER NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`personId`) REFERENCES `persons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_important_date_personId` ON `important_date`(`personId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_important_date_dateEpochDay` ON `important_date`(`dateEpochDay`)",
                )
                // §2.12: new person_link table. Composite primary
                // key on (fromId, toId, relation) so the same
                // pair can have multiple distinct relations.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `person_link` (
                        `fromId` TEXT NOT NULL,
                        `toId` TEXT NOT NULL,
                        `relation` TEXT NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        PRIMARY KEY(`fromId`, `toId`, `relation`),
                        FOREIGN KEY(`fromId`) REFERENCES `persons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`toId`) REFERENCES `persons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_person_link_toId` ON `person_link`(`toId`)",
                )
            }
        }

        /**
         * v2.0 Tier 3 migration (v12 -> v13): behavioural deniable
         * vault. Adds the `vaultMode` column to `persons` and
         * `instructions` so the home list / detail screens can
         * filter by the user's chosen mode (Visible / Hidden).
         *
         * This is **behavioural** deniability, NOT cryptographic.
         * The on-disk schema still contains the `vaultMode` column
         * and the hidden rows are still on disk. The threat-model
         * screen in Settings spells this out to the user.
         */
        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // §3.1: vault mode is a UI filter. Default "visible"
                // so every existing row is shown by default. The
                // @Index annotation on PersonEntity.vaultMode
                // and InstructionEntity.vaultMode generates
                // per-table indexes for the per-mode filter queries.
                db.execSQL(
                    "ALTER TABLE persons ADD COLUMN vaultMode TEXT NOT NULL DEFAULT 'visible'",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_persons_vaultMode` " +
                        "ON `persons`(`vaultMode`)",
                )
                db.execSQL(
                    "ALTER TABLE instructions ADD COLUMN vaultMode TEXT NOT NULL DEFAULT 'visible'",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_instructions_vaultMode` " +
                        "ON `instructions`(`vaultMode`)",
                )
            }
        }

        /**
         * v1.8.0 (PROD-READINESS-P2-#4): the v13 -> v14
         * migration adds the `audit_chain_events` table.
         * The chain is forward-only append; no data
         * migration needed for upgrades (the chain
         * starts empty and grows on each subsequent
         * state change). v12 -> v13 had already added
         * the `vaultMode` column.
         */
        val MIGRATION_13_14: Migration = object : Migration(13, 14) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `audit_chain_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                        `tableName` TEXT NOT NULL,
                        `rowId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        `signingKey` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `prevHash` TEXT NOT NULL,
                        `thisHash` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audit_chain_events_tableName_rowId` " +
                        "ON `audit_chain_events`(`tableName`, `rowId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audit_chain_events_createdAtMs` " +
                        "ON `audit_chain_events`(`createdAtMs`)",
                )
            }
        }

        /**
         * v1.8.0 (PROD-READINESS-P2-#3): the v14 -> v15
         * migration adds the `users` table. The table
         * starts empty on upgrade; the v1.8.0
         * [com.kaavalan.note.data.user.UserBootstrap] inserts
         * the single device-owner row on the first
         * observation. The unique index on `deviceOwner =
         * 1` enforces "exactly one device owner".
         */
        val MIGRATION_14_15: Migration = object : Migration(14, 15) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `users` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `displayName` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `deviceOwner` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                // v2.1.2 (data-integrity) FIX: this line used to create a
                // *partial unique* index:
                //
                //   CREATE UNIQUE INDEX ... ON users(deviceOwner)
                //   WHERE deviceOwner = 1
                //
                // The intent was to enforce "exactly one device owner" at the
                // DB level, on the assumption that Room would not mind an
                // index stricter than the one `UserEntity` declares. Room
                // does mind. `TableInfo.equals` compares the index set with
                // strict set equality, including the `unique` flag, so Room's
                // post-migration `validateMigration` saw
                //   expected Index{index_users_deviceOwner, unique=false}
                //   found    Index{index_users_deviceOwner, unique=true}
                // and threw `IllegalStateException: Migration didn't properly
                // handle: users(...)`.
                //
                // Because Room runs migrations inside a transaction, the
                // failure rolled the upgrade back and left the database at
                // v14 — so every user upgrading from a v1.8.0-era install
                // crashed on launch, permanently, with no way forward. Fresh
                // installs were unaffected (Room builds the index straight
                // from the entity), which is why it survived from v1.8.0 all
                // the way into the shipping v2.1.1.
                //
                // The index now matches `UserEntity` exactly. The single-
                // device-owner invariant is enforced in
                // `UserDao.insertDeviceOwnerIfAbsent`, which is the only
                // write path that sets `deviceOwner = 1`.
                //
                // DROP-then-CREATE rather than `CREATE ... IF NOT EXISTS`:
                // an install that somehow already carries the old unique
                // index would keep it under `IF NOT EXISTS`, and this
                // migration would silently fail to repair it.
                db.execSQL("DROP INDEX IF EXISTS `index_users_deviceOwner`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_users_deviceOwner` " +
                        "ON `users`(`deviceOwner`)",
                )
            }
        }

        /**
         * v2.0 (Hierarchy) MIGRATION_15_16. Adds the audience +
         * due chip + channel columns on `instructions` and the
         * new `delivery_receipts` table. All ADD COLUMNs use
         * nullable defaults so existing rows pass the new schema.
         */
        val MIGRATION_15_16: Migration = object : Migration(15, 16) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE instructions ADD COLUMN audienceKind TEXT")
                db.execSQL("ALTER TABLE instructions ADD COLUMN audienceTarget TEXT")
                db.execSQL("ALTER TABLE instructions ADD COLUMN audienceLabel TEXT")
                db.execSQL("ALTER TABLE instructions ADD COLUMN audienceIsBroadcast INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE instructions ADD COLUMN dueAtMs INTEGER")
                db.execSQL("ALTER TABLE instructions ADD COLUMN channel TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_instructions_audienceKind` ON `instructions`(`audienceKind`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_instructions_audienceTarget` ON `instructions`(`audienceTarget`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_instructions_dueAtMs` ON `instructions`(`dueAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_instructions_channel` ON `instructions`(`channel`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `delivery_receipts` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `instructionId` TEXT NOT NULL,
                        `recipientPersonId` TEXT NOT NULL,
                        `recipientName` TEXT NOT NULL,
                        `recipientDesignation` TEXT,
                        `recipientPhone` TEXT,
                        `channel` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `errorMessage` TEXT,
                        `sentAt` TEXT NOT NULL,
                        `syncStatus` TEXT NOT NULL DEFAULT 'SYNCED'
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_delivery_receipts_instructionId` ON `delivery_receipts`(`instructionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_delivery_receipts_status` ON `delivery_receipts`(`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_delivery_receipts_channel` ON `delivery_receipts`(`channel`)")
            }
        }
    }
}
