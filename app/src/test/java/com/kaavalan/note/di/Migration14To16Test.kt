package com.kaavalan.note.di

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import com.kaavalan.note.data.local.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.1.2 (data-integrity): the v15 -> v16 upgrade test.
 *
 * **Why this test exists.** `MIGRATION_15_16` is the migration
 * that ships in v2.1.1 — it adds the six hierarchy columns on
 * `instructions` and the `delivery_receipts` table — and before
 * this test it had **no coverage at all**. Neither did
 * `MIGRATION_14_15`. The migrations that do have tests
 * (`Migration8To9Test`, `Migration10To11Test`,
 * `Migration11To12Test`) all follow a pattern that cannot catch
 * the failure that matters: they mirror the migration's own SQL
 * into the test and then assert the columns exist. The columns
 * always exist — the test just wrote them. That assertion is
 * tautological with respect to the real risk.
 *
 * **The real risk** is the one that actually shipped a brick in
 * v1.5.7 -> v1.6.0: a migration whose resulting schema does not
 * match the schema Room derives from the `@Entity` classes. Room
 * compares the two on open (`validateMigration`) and throws
 * `IllegalStateException: Migration didn't properly handle ...`.
 * Fresh installs never hit it, because Room builds those straight
 * from the entities — only *upgrading* users crash, and upgrading
 * users are the entire existing user base.
 *
 * **What this test does differently.** It stands up a real v15
 * database, then opens it with the real [AppDatabase] and the
 * real migration, and lets Room do the comparing. Nothing is
 * mirrored; if `MIGRATION_15_16` and the entities ever disagree,
 * Room throws and this test fails.
 *
 * The v15 fixture below is the canonical Room-generated v16
 * schema with exactly the `MIGRATION_15_16` additions removed,
 * so it is a faithful v15 rather than a hand-simplified stand-in.
 *
 * The database is opened through the framework SQLite factory
 * rather than SQLCipher: Robolectric cannot load SQLCipher's
 * native library (which is why [MigrationUpgradeRegressionTest]
 * is `@Ignore`d). Encryption is orthogonal to schema validation —
 * SQLCipher is a page-level cipher and Room's schema comparison
 * runs identically on both — so the migration logic is fully
 * exercised here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Migration14To16Test {

    private val testDbName = "test-migration-14-16-${System.nanoTime()}.db"
    private lateinit var dbPath: String

    @Before
    fun setUp() {
        dbPath = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getDatabasePath(testDbName).absolutePath
    }

    @After
    fun tearDown() {
        listOf(dbPath, "$dbPath-wal", "$dbPath-shm").forEach { java.io.File(it).delete() }
    }

    /** The exact v15 schema: canonical v16 minus the MIGRATION_15_16 additions.
     *  Tables first, then indices — an index cannot be created before its table. */
    private val v15Schema: List<String> = listOf(
        """CREATE TABLE `app_state` (`id` TEXT NOT NULL, `source` TEXT NOT NULL, `key` TEXT NOT NULL, `valueJson` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))""".trimIndent(),
        """CREATE TABLE `audit_chain_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tableName` TEXT NOT NULL, `rowId` TEXT NOT NULL, `kind` TEXT NOT NULL, `payload` TEXT NOT NULL, `signingKey` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `prevHash` TEXT NOT NULL, `thisHash` TEXT NOT NULL)""".trimIndent(),
        """CREATE TABLE `captures` (`id` TEXT NOT NULL, `mode` TEXT NOT NULL, `rawText` TEXT, `audioUri` TEXT, `imageUri` TEXT, `processed` INTEGER NOT NULL, `createdAt` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, `ocrText` TEXT, `calendarEventId` TEXT, `urgency` TEXT NOT NULL, `reviewAtEpochDay` INTEGER, PRIMARY KEY(`id`))""".trimIndent(),
        """CREATE TABLE `important_date` (`id` TEXT NOT NULL, `personId` TEXT NOT NULL, `label` TEXT NOT NULL, `dateEpochDay` INTEGER NOT NULL, `recurring` INTEGER NOT NULL, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`personId`) REFERENCES `persons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""".trimIndent(),
        """CREATE TABLE `instruction_tags` (`instructionId` TEXT NOT NULL, `tagId` TEXT NOT NULL, PRIMARY KEY(`instructionId`, `tagId`), FOREIGN KEY(`instructionId`) REFERENCES `instructions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""".trimIndent(),
        """CREATE TABLE `instructions` (`id` TEXT NOT NULL, `personId` TEXT, `direction` TEXT NOT NULL, `status` TEXT NOT NULL, `source` TEXT NOT NULL, `priority` TEXT NOT NULL, `title` TEXT NOT NULL, `rawText` TEXT NOT NULL, `dueAt` TEXT, `capturedAt` TEXT NOT NULL, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, `isSensitive` INTEGER NOT NULL, `syncStatus` TEXT NOT NULL, `completedAt` TEXT, `droppedReason` TEXT, `nextActionAt` INTEGER, `caseType` TEXT, `urgency` TEXT NOT NULL, `reviewAtEpochDay` INTEGER, PRIMARY KEY(`id`))""".trimIndent(),
        """CREATE VIRTUAL TABLE `instructions_fts` USING FTS4(`title` TEXT NOT NULL, `rawText` TEXT NOT NULL, `personId` TEXT, `capturedAt` TEXT NOT NULL, tokenize=porter)""".trimIndent(),
        """CREATE TABLE `nudge_drafts` (`id` TEXT NOT NULL, `instructionId` TEXT NOT NULL, `draftText` TEXT NOT NULL, `status` TEXT NOT NULL, `sentVia` TEXT, `sentAt` TEXT, `createdAt` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, PRIMARY KEY(`id`))""".trimIndent(),
        """CREATE TABLE `person_link` (`fromId` TEXT NOT NULL, `toId` TEXT NOT NULL, `relation` TEXT NOT NULL, `createdAt` TEXT NOT NULL, PRIMARY KEY(`fromId`, `toId`, `relation`), FOREIGN KEY(`fromId`) REFERENCES `persons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`toId`) REFERENCES `persons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""".trimIndent(),
        """CREATE TABLE `persons` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `designation` TEXT, `station` TEXT, `phone` TEXT, `userId` TEXT NOT NULL, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, `isSensitive` INTEGER NOT NULL, `syncStatus` TEXT NOT NULL, `tier` TEXT NOT NULL, `cadenceOverrideDays` INTEGER, `lastInteractionAt` INTEGER, `vaultMode` TEXT NOT NULL, PRIMARY KEY(`id`))""".trimIndent(),
        """CREATE TABLE `sync_conflicts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tableName` TEXT NOT NULL, `rowId` TEXT NOT NULL, `localPayload` TEXT NOT NULL, `serverPayload` TEXT NOT NULL, `reason` TEXT NOT NULL, `detectedAt` INTEGER NOT NULL)""".trimIndent(),
        """CREATE TABLE `sync_queue` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `table` TEXT NOT NULL, `rowId` TEXT NOT NULL, `op` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `lastError` TEXT, `nextAttemptAt` INTEGER NOT NULL)""".trimIndent(),
        """CREATE TABLE `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `color` TEXT, `usageCount` INTEGER NOT NULL, `lastUsedAt` TEXT, `userId` TEXT NOT NULL, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, PRIMARY KEY(`id`))""".trimIndent(),
        """CREATE TABLE `users` (`id` TEXT NOT NULL, `displayName` TEXT NOT NULL, `role` TEXT NOT NULL, `deviceOwner` INTEGER NOT NULL, `createdAt` TEXT NOT NULL, PRIMARY KEY(`id`))""".trimIndent(),
        """CREATE INDEX `index_app_state_source` ON `app_state` (`source`)""".trimIndent(),
        """CREATE INDEX `index_audit_chain_events_createdAtMs` ON `audit_chain_events` (`createdAtMs`)""".trimIndent(),
        """CREATE INDEX `index_audit_chain_events_tableName_rowId` ON `audit_chain_events` (`tableName`, `rowId`)""".trimIndent(),
        """CREATE INDEX `index_captures_mode` ON `captures` (`mode`)""".trimIndent(),
        """CREATE INDEX `index_captures_processed` ON `captures` (`processed`)""".trimIndent(),
        """CREATE INDEX `index_captures_syncStatus` ON `captures` (`syncStatus`)""".trimIndent(),
        """CREATE INDEX `index_captures_urgency` ON `captures` (`urgency`)""".trimIndent(),
        """CREATE INDEX `index_important_date_dateEpochDay` ON `important_date` (`dateEpochDay`)""".trimIndent(),
        """CREATE INDEX `index_important_date_personId` ON `important_date` (`personId`)""".trimIndent(),
        """CREATE INDEX `index_instruction_tags_tagId` ON `instruction_tags` (`tagId`)""".trimIndent(),
        """CREATE INDEX `index_instructions_dueAt` ON `instructions` (`dueAt`)""".trimIndent(),
        """CREATE INDEX `index_instructions_personId` ON `instructions` (`personId`)""".trimIndent(),
        """CREATE INDEX `index_instructions_status` ON `instructions` (`status`)""".trimIndent(),
        """CREATE INDEX `index_instructions_syncStatus` ON `instructions` (`syncStatus`)""".trimIndent(),
        """CREATE INDEX `index_instructions_urgency` ON `instructions` (`urgency`)""".trimIndent(),
        """CREATE INDEX `index_nudge_drafts_instructionId` ON `nudge_drafts` (`instructionId`)""".trimIndent(),
        """CREATE INDEX `index_nudge_drafts_status` ON `nudge_drafts` (`status`)""".trimIndent(),
        """CREATE INDEX `index_person_link_toId` ON `person_link` (`toId`)""".trimIndent(),
        """CREATE INDEX `index_persons_lastInteractionAt` ON `persons` (`lastInteractionAt`)""".trimIndent(),
        """CREATE INDEX `index_persons_name` ON `persons` (`name`)""".trimIndent(),
        """CREATE INDEX `index_persons_syncStatus` ON `persons` (`syncStatus`)""".trimIndent(),
        """CREATE INDEX `index_persons_vaultMode` ON `persons` (`vaultMode`)""".trimIndent(),
        """CREATE INDEX `index_sync_conflicts_detectedAt` ON `sync_conflicts` (`detectedAt`)""".trimIndent(),
        """CREATE INDEX `index_sync_conflicts_tableName_rowId` ON `sync_conflicts` (`tableName`, `rowId`)""".trimIndent(),
        """CREATE INDEX `index_sync_queue_nextAttemptAt` ON `sync_queue` (`nextAttemptAt`)""".trimIndent(),
        """CREATE UNIQUE INDEX `index_sync_queue_op_table_rowId` ON `sync_queue` (`op`, `table`, `rowId`)""".trimIndent(),
        """CREATE INDEX `index_tags_kind` ON `tags` (`kind`)""".trimIndent(),
        """CREATE INDEX `index_tags_syncStatus` ON `tags` (`syncStatus`)""".trimIndent(),
        """CREATE INDEX `index_users_deviceOwner` ON `users` (`deviceOwner`)""".trimIndent()
    )

    private fun buildV15Fixture() {
        SQLiteDatabase.openOrCreateDatabase(dbPath, null).use { db ->
            v15Schema.forEach { db.execSQL(it) }
            // One realistic pre-upgrade instruction. If the migration
            // rebuilt the table instead of ALTER-ing it, this row would
            // vanish and the assertion below would catch it.
            db.execSQL(
                """
                INSERT INTO instructions
                  (id, personId, direction, status, source, priority, title, rawText,
                   dueAt, capturedAt, createdAt, updatedAt, isSensitive, syncStatus, urgency)
                VALUES
                  ('i-pre-upgrade', 'p-inba', 'OUTGOING', 'OPEN', 'TEXT', 'NORMAL',
                   'FIR 47 follow up', 'send the FIR 47 status to Inba by Tuesday',
                   NULL, '2026-08-12T00:00:00+00:00', '2026-08-12T00:00:00+00:00',
                   '2026-08-12T00:00:00+00:00', 0, 'SYNCED', 'NORMAL')
                """.trimIndent(),
            )
            db.version = 15
        }
    }

    /**
     * The core assertion. Opening the v15 database through the real
     * [AppDatabase] makes Room run `MIGRATION_15_16` and then compare
     * the resulting schema against the one derived from the entity
     * classes. A mismatch throws here.
     */
    @Test
    fun `v15 database upgrades to v16 and passes Room schema validation`() {
        buildV15Fixture()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbPath)
            .addMigrations(AppDatabase.MIGRATION_15_16)
            .build()

        // Room is lazy: the migration + validation only run on first
        // access to the underlying database, not on build().
        val version = db.openHelper.writableDatabase.version
        assertEquals("database should be at v16 after the migration", 16, version)
        db.close()
    }

    @Test
    fun `existing instruction rows survive the upgrade`() {
        buildV15Fixture()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbPath)
            .addMigrations(AppDatabase.MIGRATION_15_16)
            .build()
        db.openHelper.writableDatabase.query(
            "SELECT title, audienceKind, audienceIsBroadcast FROM instructions WHERE id = 'i-pre-upgrade'",
        ).use { c ->
            assertTrue("the pre-upgrade instruction row must survive the migration", c.moveToFirst())
            assertEquals("FIR 47 follow up", c.getString(0))
            assertTrue("audienceKind is nullable and unset for pre-upgrade rows", c.isNull(1))
            assertEquals(
                "audienceIsBroadcast is NOT NULL DEFAULT 0, so old rows must read 0",
                0,
                c.getInt(2),
            )
        }
        db.close()
    }

    @Test
    fun `delivery_receipts table is created and writable after the upgrade`() {
        buildV15Fixture()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbPath)
            .addMigrations(AppDatabase.MIGRATION_15_16)
            .build()
        db.openHelper.writableDatabase.let { raw ->
            raw.execSQL(
                """
                INSERT INTO delivery_receipts
                  (id, instructionId, recipientPersonId, recipientName, recipientDesignation,
                   recipientPhone, channel, status, errorMessage, sentAt, syncStatus)
                VALUES
                  ('r-1', 'i-pre-upgrade', 'p-inba', 'Inba', 'SI', NULL,
                   'WHATSAPP', 'SENT', NULL, '2026-09-03T00:00:00+00:00', 'SYNCED')
                """.trimIndent(),
            )
            raw.query("SELECT COUNT(*) FROM delivery_receipts").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
        }
        db.close()
    }

    /**
     * The v14 schema: the v15 fixture minus the `users` table and its
     * partial unique index, which is exactly what `MIGRATION_14_15`
     * adds. `MIGRATION_14_15` had no test before this one either.
     */
    private val v14Schema: List<String> = v15Schema.filterNot {
        it.contains("`users`") || it.contains("index_users_deviceOwner")
    }

    private fun buildV14Fixture() {
        SQLiteDatabase.openOrCreateDatabase(dbPath, null).use { db ->
            v14Schema.forEach { db.execSQL(it) }
            db.execSQL(
                """
                INSERT INTO instructions
                  (id, personId, direction, status, source, priority, title, rawText,
                   dueAt, capturedAt, createdAt, updatedAt, isSensitive, syncStatus, urgency)
                VALUES
                  ('i-v14', 'p-uma', 'OUTGOING', 'OPEN', 'TEXT', 'NORMAL',
                   'Morning brief prep', 'prep the morning brief notes for tomorrow',
                   NULL, '2026-08-12T00:00:00+00:00', '2026-08-12T00:00:00+00:00',
                   '2026-08-12T00:00:00+00:00', 0, 'SYNCED', 'NORMAL')
                """.trimIndent(),
            )
            db.version = 14
        }
    }

    /**
     * The chain an actual v2.0-era user upgrading to v2.1.1 walks:
     * 14 -> 15 -> 16 in one open. Room applies both migrations in
     * sequence and validates the end state against the entities.
     */
    @Test
    fun `v14 database upgrades through the full chain to v16`() {
        buildV14Fixture()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbPath)
            .addMigrations(AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16)
            .build()

        assertEquals(
            "database should be at v16 after the 14 -> 15 -> 16 chain",
            16,
            db.openHelper.writableDatabase.version,
        )
        db.close()
    }

    /**
     * v2.1.2: the regression guard for the v14 -> v15 brick.
     *
     * `MIGRATION_14_15` used to create a *partial unique* index on
     * `users(deviceOwner)`, while `UserEntity` declares a plain
     * non-unique one. Room compares index sets with strict equality
     * including the `unique` flag, so `validateMigration` threw and
     * the upgrade rolled back — permanently bricking the launch for
     * anyone coming from a v1.8.0-era install.
     *
     * The `v14 database upgrades through the full chain to v16` test
     * above is what actually catches a recurrence (it fails on the
     * Room validation). This test pins the specific property that
     * was wrong, so a future change that reintroduces a unique index
     * fails with an obvious message rather than a schema dump.
     */
    @Test
    fun `deviceOwner index is plain and non-unique, matching UserEntity`() {
        buildV14Fixture()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbPath)
            .addMigrations(AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16)
            .build()
        db.openHelper.writableDatabase.query(
            "SELECT `unique`, partial FROM pragma_index_list('users') " +
                "WHERE name = 'index_users_deviceOwner'",
        ).use { c ->
            assertTrue("index_users_deviceOwner must exist on users", c.moveToFirst())
            assertEquals(
                "index_users_deviceOwner must be NON-unique to match UserEntity's " +
                    "@Index(value = [\"deviceOwner\"]); a unique index makes Room's " +
                    "validateMigration reject the v14 -> v15 upgrade",
                0,
                c.getInt(0),
            )
            assertEquals(
                "index_users_deviceOwner must not be partial — Room does not model " +
                    "partial indices and will not match one",
                0,
                c.getInt(1),
            )
        }
        db.close()
    }

    /**
     * The invariant the partial unique index used to guarantee now
     * lives in `UserDao.insertDeviceOwnerIfAbsent`. Assert the table
     * still accepts the rows it should, so the fix did not trade one
     * breakage for another.
     */
    @Test
    fun `users table accepts one owner and multiple non-owners after the chain`() {
        buildV14Fixture()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbPath)
            .addMigrations(AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16)
            .build()
        val raw = db.openHelper.writableDatabase
        raw.execSQL(
            "INSERT INTO users (id, displayName, role, deviceOwner, createdAt) " +
                "VALUES ('u-1', 'Device Owner', 'SENIOR_OFFICER', 1, '2026-09-03T00:00:00+00:00')",
        )
        raw.execSQL(
            "INSERT INTO users (id, displayName, role, deviceOwner, createdAt) " +
                "VALUES ('u-2', 'Colleague', 'OFFICER', 0, '2026-09-03T00:00:00+00:00')",
        )
        raw.execSQL(
            "INSERT INTO users (id, displayName, role, deviceOwner, createdAt) " +
                "VALUES ('u-3', 'Colleague Two', 'OFFICER', 0, '2026-09-03T00:00:00+00:00')",
        )
        raw.query("SELECT COUNT(*) FROM users").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3, c.getInt(0))
        }
        db.close()
    }
}
