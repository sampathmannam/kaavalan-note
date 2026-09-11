package com.kaavalan.note.data.subdivision

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import com.kaavalan.note.data.local.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 17 to 18 upgrade, run against a real, populated v17 database.
 *
 * The failure this guards against is the one that actually bricked v1.5.7 to v1.6.0: a
 * migration whose resulting schema does not match what Room derives from the `@Entity`
 * classes. Fresh installs never hit it - Room builds those from the entities - so only
 * upgrading users crash, and upgrading users are the entire existing user base. Opening
 * the migrated file through the real [AppDatabase] makes Room do the comparing.
 *
 * The fixture is deliberately populated with the things an officer would be most upset to
 * lose: notes with journals, dates, tags, a label link, a relationship, an important date
 * and a hidden-vault contact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SubdivisionMigration17To18Test {

    private val testDbName = "test-migration-17-18-${System.nanoTime()}.db"
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

    /** The canonical v17 schema: v18 minus everything SUBDIVISION_MIGRATION_17_18 adds. */
    private val v17Schema: List<String> = listOf(
        "CREATE TABLE `app_state` (`id` TEXT NOT NULL, `source` TEXT NOT NULL, `key` TEXT NOT NULL, " +
            "`valueJson` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE `audit_chain_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`tableName` TEXT NOT NULL, `rowId` TEXT NOT NULL, `kind` TEXT NOT NULL, `payload` TEXT NOT NULL, " +
            "`signingKey` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `prevHash` TEXT NOT NULL, " +
            "`thisHash` TEXT NOT NULL)",
        "CREATE TABLE `captures` (`id` TEXT NOT NULL, `mode` TEXT NOT NULL, `rawText` TEXT, `audioUri` TEXT, " +
            "`imageUri` TEXT, `processed` INTEGER NOT NULL, `createdAt` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, " +
            "`ocrText` TEXT, `calendarEventId` TEXT, `urgency` TEXT NOT NULL, `reviewAtEpochDay` INTEGER, " +
            "PRIMARY KEY(`id`))",
        "CREATE TABLE `delivery_receipts` (`id` TEXT NOT NULL, `instructionId` TEXT NOT NULL, " +
            "`recipientPersonId` TEXT NOT NULL, `recipientName` TEXT NOT NULL, `recipientDesignation` TEXT, " +
            "`recipientPhone` TEXT, `channel` TEXT NOT NULL, `status` TEXT NOT NULL, `errorMessage` TEXT, " +
            "`sentAt` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE `important_date` (`id` TEXT NOT NULL, `personId` TEXT NOT NULL, `label` TEXT NOT NULL, " +
            "`dateEpochDay` INTEGER NOT NULL, `recurring` INTEGER NOT NULL, `createdAt` TEXT NOT NULL, " +
            "`updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`personId`) REFERENCES `persons`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE `instruction_tags` (`instructionId` TEXT NOT NULL, `tagId` TEXT NOT NULL, " +
            "PRIMARY KEY(`instructionId`, `tagId`), FOREIGN KEY(`instructionId`) REFERENCES `instructions`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE `instructions` (`id` TEXT NOT NULL, `personId` TEXT, `direction` TEXT NOT NULL, " +
            "`status` TEXT NOT NULL, `source` TEXT NOT NULL, `priority` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`rawText` TEXT NOT NULL, `dueAt` TEXT, `capturedAt` TEXT NOT NULL, `createdAt` TEXT NOT NULL, " +
            "`updatedAt` TEXT NOT NULL, `isSensitive` INTEGER NOT NULL, `syncStatus` TEXT NOT NULL, " +
            "`completedAt` TEXT, `droppedReason` TEXT, `nextActionAt` INTEGER, `caseType` TEXT, " +
            "`urgency` TEXT NOT NULL, `reviewAtEpochDay` INTEGER, `audienceKind` TEXT, `audienceTarget` TEXT, " +
            "`audienceLabel` TEXT, `audienceIsBroadcast` INTEGER NOT NULL DEFAULT 0, `dueAtMs` INTEGER, " +
            "`channel` TEXT, `deadlineAtMs` INTEGER, `updatesJson` TEXT NOT NULL DEFAULT '[]', " +
            "PRIMARY KEY(`id`))",
        "CREATE VIRTUAL TABLE `instructions_fts` USING FTS4(`title` TEXT NOT NULL, `rawText` TEXT NOT NULL, " +
            "`personId` TEXT, `capturedAt` TEXT NOT NULL, tokenize=porter)",
        "CREATE TABLE `nudge_drafts` (`id` TEXT NOT NULL, `instructionId` TEXT NOT NULL, " +
            "`draftText` TEXT NOT NULL, `status` TEXT NOT NULL, `sentVia` TEXT, `sentAt` TEXT, " +
            "`createdAt` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE `person_link` (`fromId` TEXT NOT NULL, `toId` TEXT NOT NULL, `relation` TEXT NOT NULL, " +
            "`createdAt` TEXT NOT NULL, PRIMARY KEY(`fromId`, `toId`, `relation`), FOREIGN KEY(`fromId`) " +
            "REFERENCES `persons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`toId`) " +
            "REFERENCES `persons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE `persons` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `designation` TEXT, `station` TEXT, " +
            "`phone` TEXT, `userId` TEXT NOT NULL, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, " +
            "`isSensitive` INTEGER NOT NULL, `syncStatus` TEXT NOT NULL, `tier` TEXT NOT NULL, " +
            "`cadenceOverrideDays` INTEGER, `lastInteractionAt` INTEGER, `vaultMode` TEXT NOT NULL, " +
            "PRIMARY KEY(`id`))",
        "CREATE TABLE `sync_conflicts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`tableName` TEXT NOT NULL, `rowId` TEXT NOT NULL, `localPayload` TEXT NOT NULL, " +
            "`serverPayload` TEXT NOT NULL, `reason` TEXT NOT NULL, `detectedAt` INTEGER NOT NULL)",
        "CREATE TABLE `sync_queue` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `table` TEXT NOT NULL, " +
            "`rowId` TEXT NOT NULL, `op` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`attempts` INTEGER NOT NULL, `lastError` TEXT, `nextAttemptAt` INTEGER NOT NULL)",
        "CREATE TABLE `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `color` TEXT, " +
            "`usageCount` INTEGER NOT NULL, `lastUsedAt` TEXT, `userId` TEXT NOT NULL, `createdAt` TEXT NOT NULL, " +
            "`updatedAt` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE `users` (`id` TEXT NOT NULL, `displayName` TEXT NOT NULL, `role` TEXT NOT NULL, " +
            "`deviceOwner` INTEGER NOT NULL, `createdAt` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX `index_app_state_source` ON `app_state` (`source`)",
        "CREATE INDEX `index_audit_chain_events_createdAtMs` ON `audit_chain_events` (`createdAtMs`)",
        "CREATE INDEX `index_audit_chain_events_tableName_rowId` ON `audit_chain_events` (`tableName`, `rowId`)",
        "CREATE INDEX `index_captures_mode` ON `captures` (`mode`)",
        "CREATE INDEX `index_captures_processed` ON `captures` (`processed`)",
        "CREATE INDEX `index_captures_syncStatus` ON `captures` (`syncStatus`)",
        "CREATE INDEX `index_captures_urgency` ON `captures` (`urgency`)",
        "CREATE INDEX `index_delivery_receipts_instructionId` ON `delivery_receipts` (`instructionId`)",
        "CREATE INDEX `index_delivery_receipts_channel` ON `delivery_receipts` (`channel`)",

        "CREATE INDEX `index_delivery_receipts_status` ON `delivery_receipts` (`status`)",
        "CREATE INDEX `index_important_date_dateEpochDay` ON `important_date` (`dateEpochDay`)",
        "CREATE INDEX `index_important_date_personId` ON `important_date` (`personId`)",
        "CREATE INDEX `index_instruction_tags_tagId` ON `instruction_tags` (`tagId`)",
        "CREATE INDEX `index_instructions_audienceKind` ON `instructions` (`audienceKind`)",
        "CREATE INDEX `index_instructions_audienceTarget` ON `instructions` (`audienceTarget`)",
        "CREATE INDEX `index_instructions_channel` ON `instructions` (`channel`)",
        "CREATE INDEX `index_instructions_dueAt` ON `instructions` (`dueAt`)",
        "CREATE INDEX `index_instructions_dueAtMs` ON `instructions` (`dueAtMs`)",
        "CREATE INDEX `index_instructions_personId` ON `instructions` (`personId`)",
        "CREATE INDEX `index_instructions_status` ON `instructions` (`status`)",
        "CREATE INDEX `index_instructions_syncStatus` ON `instructions` (`syncStatus`)",
        "CREATE INDEX `index_instructions_urgency` ON `instructions` (`urgency`)",
        "CREATE INDEX `index_nudge_drafts_instructionId` ON `nudge_drafts` (`instructionId`)",
        "CREATE INDEX `index_nudge_drafts_status` ON `nudge_drafts` (`status`)",
        "CREATE INDEX `index_person_link_toId` ON `person_link` (`toId`)",
        "CREATE INDEX `index_persons_lastInteractionAt` ON `persons` (`lastInteractionAt`)",
        "CREATE INDEX `index_persons_name` ON `persons` (`name`)",
        "CREATE INDEX `index_persons_syncStatus` ON `persons` (`syncStatus`)",
        "CREATE INDEX `index_persons_vaultMode` ON `persons` (`vaultMode`)",
        "CREATE INDEX `index_sync_conflicts_detectedAt` ON `sync_conflicts` (`detectedAt`)",
        "CREATE INDEX `index_sync_conflicts_tableName_rowId` ON `sync_conflicts` (`tableName`, `rowId`)",
        "CREATE INDEX `index_sync_queue_nextAttemptAt` ON `sync_queue` (`nextAttemptAt`)",
        "CREATE UNIQUE INDEX `index_sync_queue_op_table_rowId` ON `sync_queue` (`op`, `table`, `rowId`)",
        "CREATE INDEX `index_tags_kind` ON `tags` (`kind`)",
        "CREATE INDEX `index_tags_syncStatus` ON `tags` (`syncStatus`)",
        "CREATE INDEX `index_users_deviceOwner` ON `users` (`deviceOwner`)",
    )

    private fun person(
        id: String,
        name: String,
        station: String?,
        vaultMode: String = "visible",
        sensitive: Int = 0,
    ) = "INSERT INTO persons (id, name, designation, station, phone, userId, createdAt, updatedAt, " +
        "isSensitive, syncStatus, tier, cadenceOverrideDays, lastInteractionAt, vaultMode) VALUES " +
        "('$id', '$name', 'SI', ${station?.let { "'$it'" } ?: "NULL"}, '+91-9000000001', 'u1', " +
        "'2026-07-01T00:00:00Z', '2026-08-01T00:00:00Z', $sensitive, 'SYNCED', 'Active', NULL, NULL, '$vaultMode')"

    private fun instruction(id: String, personId: String?, journal: String) =
        "INSERT INTO instructions (id, personId, direction, status, source, priority, title, rawText, dueAt, " +
            "capturedAt, createdAt, updatedAt, isSensitive, syncStatus, urgency, audienceIsBroadcast, " +
            "deadlineAtMs, updatesJson) VALUES ('$id', ${personId?.let { "'$it'" } ?: "NULL"}, 'OUTGOING', " +
            "'OPEN', 'TEXT', 'NORMAL', 'Title $id', 'Body of $id', NULL, '2026-08-01T00:00:00Z', " +
            "'2026-08-01T00:00:00Z', '2026-08-02T00:00:00Z', 0, 'SYNCED', 'normal', 0, 1799999999000, '$journal')"

    private fun buildPopulatedV17() {
        SQLiteDatabase.openOrCreateDatabase(dbPath, null).use { db ->
            v17Schema.forEach { db.execSQL(it) }
            // Two spellings of the same station, plus surrounding whitespace: all three
            // must fold to one station row.
            db.execSQL(person("p-visible-1", "Ramesh", "Kalakad"))
            db.execSQL(person("p-visible-2", "Kavitha", "KALAKAD"))
            db.execSQL(person("p-visible-3", "Selvam", "  Kalakad  "))
            db.execSQL(person("p-visible-4", "Meena", "Nanguneri"))
            db.execSQL(person("p-visible-5", "Arun", null))
            db.execSQL(person("p-visible-6", "Blank", "   "))
            // A non-Latin name must keep every character, and must not fold into the
            // Latin ones.
            db.execSQL(person("p-visible-7", "Tamil", "நாங்குநேரி"))
            // The same station name in the hidden vault is a DIFFERENT station.
            db.execSQL(person("p-hidden-1", "Hidden One", "Kalakad", vaultMode = "hidden"))
            db.execSQL(person("p-sensitive", "Sensitive", "Kalakad", sensitive = 1))

            db.execSQL(instruction("i-1", "p-visible-1", "[]"))
            db.execSQL(
                instruction(
                    "i-2",
                    "p-visible-2",
                    """[{"id":"u1","at":"2026-08-02T00:00:00Z","text":"Spoke to the SI","status":"IN_PROGRESS","nextFollowUpAtMs":null}]""",
                ),
            )
            db.execSQL(instruction("i-unassigned", null, "[]"))
            db.execSQL(instruction("i-hidden", "p-hidden-1", "[]"))
            db.execSQL(
                "INSERT INTO instructions (id, personId, direction, status, source, priority, title, rawText, " +
                    "dueAt, capturedAt, createdAt, updatedAt, isSensitive, syncStatus, urgency, " +
                    "audienceKind, audienceTarget, audienceLabel, audienceIsBroadcast, updatesJson) VALUES " +
                    "('i-audience', NULL, 'OUTGOING', 'OPEN', 'TEXT', 'NORMAL', 'Broadcast', 'To Meena', NULL, " +
                    "'2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', 0, 'SYNCED', " +
                    "'normal', 'PERSON', 'p-visible-4', 'Meena', 0, '[]')",
            )
            db.execSQL(
                "INSERT INTO tags (id, name, kind, color, usageCount, lastUsedAt, userId, createdAt, updatedAt, " +
                    "syncStatus) VALUES ('t-1', 'urgent', 'FREE', NULL, 3, NULL, 'u1', '2026-07-01T00:00:00Z', " +
                    "'2026-07-01T00:00:00Z', 'SYNCED')",
            )
            db.execSQL("INSERT INTO instruction_tags (instructionId, tagId) VALUES ('i-1', 't-1')")
            db.execSQL(
                "INSERT INTO person_link (fromId, toId, relation, createdAt) VALUES " +
                    "('p-visible-1', 'p-visible-2', 'Reports to', '2026-07-02T00:00:00Z')",
            )
            db.execSQL(
                "INSERT INTO important_date (id, personId, label, dateEpochDay, recurring, createdAt, updatedAt) " +
                    "VALUES ('d-1', 'p-visible-1', 'First met', 19560, 0, '2026-07-02T00:00:00Z', " +
                    "'2026-07-02T00:00:00Z')",
            )
            db.version = 17
        }
    }

    private fun openMigrated(): AppDatabase {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return Room.databaseBuilder(ctx, AppDatabase::class.java, dbPath)
            .addMigrations(SUBDIVISION_MIGRATION_17_18)
            .build()
    }

    @Test
    fun `a populated v17 database upgrades to v18 and passes Room schema validation`() {
        buildPopulatedV17()
        val db = openMigrated()
        // Room is lazy: the migration and its schema comparison only run on first access.
        assertEquals("the database must be at v18 after the migration", 18, db.openHelper.writableDatabase.version)
        db.close()
    }

    @Test
    fun `contacts notes journals dates tags links and hidden rows all survive the upgrade`() = runBlocking {
        buildPopulatedV17()
        val db = openMigrated()
        val raw = db.openHelper.writableDatabase
        raw.query("SELECT COUNT(*) FROM persons").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("every contact must survive", 9, c.getInt(0))
        }
        raw.query("SELECT COUNT(*) FROM instructions").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("every instruction must survive", 5, c.getInt(0))
        }
        raw.query("SELECT updatesJson, deadlineAtMs, updatedAt FROM instructions WHERE id = 'i-2'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("the journal text must survive verbatim", c.getString(0).contains("Spoke to the SI"))
            assertEquals("an existing deadline must not move", 1799999999000L, c.getLong(1))
            assertEquals("timestamps must not be rewritten", "2026-08-02T00:00:00Z", c.getString(2))
        }
        raw.query("SELECT COUNT(*) FROM instruction_tags").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("label links must survive", 1, c.getInt(0))
        }
        raw.query("SELECT COUNT(*) FROM person_link").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("relationships must survive", 1, c.getInt(0))
        }
        raw.query("SELECT COUNT(*) FROM important_date").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("important dates must survive", 1, c.getInt(0))
        }
        raw.query("SELECT name, phone FROM persons WHERE id = 'p-hidden-1'").use { c ->
            assertTrue("a hidden-vault contact must survive", c.moveToFirst())
            assertEquals("Hidden One", c.getString(0))
            assertEquals("an imported phone number must survive intact", "+91-9000000001", c.getString(1))
        }
        db.close()
    }

    @Test
    fun `station backfill deduplicates per vault and keeps a non-Latin name intact`() = runBlocking {
        buildPopulatedV17()
        val db = openMigrated()
        val stations = db.subdivisionDao().stations()
        // visible: Kalakad (three spellings folded), Nanguneri, the Tamil name.
        // hidden: its own Kalakad.
        assertEquals(
            "expected 3 visible + 1 hidden station; got ${stations.map { it.vaultMode + ":" + it.name }}",
            4,
            stations.size,
        )
        val visible = stations.filter { it.vaultMode == "visible" }
        assertEquals(3, visible.size)
        val kalakadVisible = visible.single { it.nameKey == "kalakad" }
        val kalakadHidden = stations.single { it.vaultMode == "hidden" }
        assertTrue(
            "a station name must never be shared across vaults",
            kalakadVisible.id != kalakadHidden.id,
        )
        assertNotNull(
            "the non-Latin station name must be preserved character for character",
            visible.singleOrNull { it.name == "நாங்குநேரி" },
        )
        assertEquals(
            "every default unit type must be Station",
            listOf("Station"),
            stations.map { it.kind }.distinct(),
        )

        val people = db.personDao().snapshot().associateBy { it.id }
        listOf("p-visible-1", "p-visible-2", "p-visible-3").forEach { id ->
            assertEquals(
                "$id must be stamped with the single folded Kalakad station",
                kalakadVisible.id,
                people.getValue(id).stationId,
            )
        }
        assertEquals(
            "the hidden contact must point at the hidden vault's own station",
            kalakadHidden.id,
            people.getValue("p-hidden-1").stationId,
        )
        assertNull("a contact with no station text gets no station", people.getValue("p-visible-5").stationId)
        assertNull("a whitespace-only station is not a station", people.getValue("p-visible-6").stationId)
        db.close()
    }

    @Test
    fun `existing work is stamped with the station it was recorded at`() = runBlocking {
        buildPopulatedV17()
        val db = openMigrated()
        val people = db.personDao().snapshot().associateBy { it.id }
        val instructions = db.instructionDao().snapshot().associateBy { it.id }
        assertEquals(
            "the instruction takes its responsible contact's station",
            people.getValue("p-visible-1").stationId,
            instructions.getValue("i-1").stationId,
        )
        assertEquals(
            "an audience-only link also resolves to a station",
            people.getValue("p-visible-4").stationId,
            instructions.getValue("i-audience").stationId,
        )
        assertNull(
            "an unassigned capture has no station to inherit",
            instructions.getValue("i-unassigned").stationId,
        )
        assertNull("no instruction is linked to a matter by the migration", instructions.getValue("i-1").matterId)
        db.close()
    }

    @Test
    fun `the upgrade seeds no subdivision and classifies nobody as staff`() = runBlocking {
        buildPopulatedV17()
        val db = openMigrated()
        assertTrue(
            "a migration must never put a subdivision name in the officer's mouth",
            db.subdivisionDao().profiles().isEmpty(),
        )
        assertTrue("no matter may be invented", db.subdivisionDao().matters().isEmpty())
        assertTrue("no posting history may be invented", db.subdivisionDao().postings().isEmpty())
        assertTrue("no review may be invented", db.subdivisionDao().reviews().isEmpty())
        db.personDao().snapshot().forEach { person ->
            assertFalse(
                "${person.name} must not be classified as staff by a migration",
                person.isStaff,
            )
            assertEquals("responsibilities must start empty", "", person.responsibilities)
            assertTrue("the staffActive default must be true", person.staffActive)
        }
        db.close()
    }

    @Test
    fun `the new tables and columns are writable after the upgrade`() = runBlocking {
        buildPopulatedV17()
        val db = openMigrated()
        val raw = db.openHelper.writableDatabase
        raw.execSQL(
            "INSERT INTO subdivision_profile (vaultMode, name, district, officerName, updatedAt) VALUES " +
                "('visible', 'Ambasamudram', 'Tirunelveli', 'K. S.', '2026-09-10T00:00:00Z')",
        )
        raw.query("SELECT name FROM subdivision_profile WHERE vaultMode = 'visible'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("Ambasamudram", c.getString(0))
        }
        // The uniqueness guarantee the whole station model rests on.
        val existing = db.subdivisionDao().stations().first { it.vaultMode == "visible" }
        val duplicate = runCatching {
            raw.execSQL(
                "INSERT INTO stations (id, vaultMode, name, nameKey, kind, notes, archived, createdAt, updatedAt) " +
                    "VALUES ('dup', 'visible', '${existing.name}', '${existing.nameKey}', 'Station', '', 0, " +
                    "'2026-09-10T00:00:00Z', '2026-09-10T00:00:00Z')",
            )
        }
        assertTrue(
            "the unique index must reject a duplicate station name in the same vault",
            duplicate.isFailure,
        )
        db.close()
    }
}
