package com.kaavalan.note.data.export

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaavalan.note.data.instructions.InstructionJournal
import com.kaavalan.note.data.instructions.InstructionUpdate
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.entities.CaptureEntity
import com.kaavalan.note.data.local.entities.ImportantDateEntity
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.InstructionTagCrossRef
import com.kaavalan.note.data.local.entities.PersonEntity
import com.kaavalan.note.data.local.entities.PersonLinkEntity
import com.kaavalan.note.data.local.entities.TagEntity
import com.kaavalan.note.data.subdivision.Matter
import com.kaavalan.note.data.subdivision.StaffPosting
import com.kaavalan.note.data.subdivision.Station
import com.kaavalan.note.data.subdivision.SubdivisionProfile
import com.kaavalan.note.data.subdivision.SubdivisionRepository
import com.kaavalan.note.data.subdivision.SubdivisionReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
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
import java.io.File

/**
 * The manual backup, exercised against a real in-memory Room database.
 *
 * v2.6.0 replaced the mocked-DAO version of this test. Mocks could show that `restore()`
 * reported plausible counts, but not that a row actually came back, that a repeated
 * restore did not destroy a child row through an ON DELETE CASCADE, or that a refused
 * file left the database untouched. Those are precisely the properties recovery depends
 * on, so the test now wipes the tables and asserts the rows are genuinely back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupRoundTripTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var backupManager: BackupManager

    private val now = "2026-08-15T10:00:00Z"

    private val station = Station(
        id = "st-1",
        vaultMode = "visible",
        name = "Kalakad",
        nameKey = SubdivisionRepository.stationKey("Kalakad"),
        kind = "Station",
        notes = "Coastal beat, two outposts.\n\nSecond paragraph, with a comma.",
        createdAt = now,
        updatedAt = now,
    )

    private val matter = Matter(
        id = "m-1",
        vaultMode = "visible",
        title = "Sand mining inquiry",
        stationId = "st-1",
        reference = "REF/2026/14",
        description = "Quoted \"context\" with, commas and தமிழ் text.",
        createdAt = now,
        updatedAt = now,
    )

    private val testPeople = listOf(
        PersonEntity(
            id = "p1",
            name = "DSP Srinagar",
            designation = "DSP",
            station = "Kalakad",
            phone = "+91-9876543210",
            userId = "u1",
            createdAt = "2026-08-01T10:00:00Z",
            updatedAt = "2026-08-15T11:00:00Z",
            isSensitive = true,
            tier = "Inner",
            cadenceOverrideDays = null,
            lastInteractionAt = 1725000000000L,
            vaultMode = "visible",
            stationId = "st-1",
            isStaff = true,
            staffActive = true,
            responsibilities = "Coastal beat, sand mining, night patrol",
        ),
        PersonEntity(
            id = "p2",
            name = "SHO Ramu",
            designation = "SHO",
            station = "Station 7",
            phone = null,
            userId = "u1",
            createdAt = "2026-08-05T10:00:00Z",
            updatedAt = "2026-08-15T11:00:00Z",
            isSensitive = false,
            tier = "Active",
            cadenceOverrideDays = 14,
            lastInteractionAt = null,
            vaultMode = "visible",
        ),
    )

    private val testInstructions = listOf(
        InstructionEntity(
            id = "i1",
            personId = "p1",
            direction = "OUTGOING",
            status = "OPEN",
            source = "TEXT",
            priority = "HIGH",
            title = "Send FIR 47",
            rawText = "Send FIR 47 to SP by Friday",
            deadlineAtMs = 1799999999000L,
            dueAtMs = 1789999999000L,
            audienceKind = "PERSON",
            audienceTarget = "p1",
            audienceLabel = "DSP Srinagar",
            channel = "SHARE",
            updatesJson = InstructionJournal.encode(
                listOf(
                    InstructionUpdate(
                        "u1",
                        "2026-08-15T10:00:00Z",
                        "Called; report tomorrow. தமிழ்",
                        "IN_PROGRESS",
                        1789999999000L,
                    ),
                ),
            ),
            dueAt = "2026-08-22T15:00:00+05:30",
            capturedAt = "2026-08-15T10:00:00Z",
            createdAt = "2026-08-15T10:00:00Z",
            updatedAt = "2026-08-15T10:00:00Z",
            isSensitive = false,
            completedAt = null,
            droppedReason = null,
            nextActionAt = 1725000000000L,
            caseType = "FIR",
            urgency = "normal",
            reviewAtEpochDay = null,
            stationId = "st-1",
            matterId = "m-1",
        ),
    )

    private val testTags = listOf(
        TagEntity(
            id = "t1",
            name = "urgent",
            kind = "FREE",
            color = "#FF0000",
            usageCount = 5,
            lastUsedAt = "2026-08-15T10:00:00Z",
            userId = "u1",
            createdAt = "2026-08-10T10:00:00Z",
            updatedAt = "2026-08-15T10:00:00Z",
        ),
    )

    private val testCaptures = listOf(
        CaptureEntity(
            id = "c1",
            mode = "TEXT",
            rawText = "Send FIR 47 to SP by Friday",
            audioUri = null,
            imageUri = null,
            processed = true,
            createdAt = "2026-08-15T10:00:00Z",
            ocrText = null,
            calendarEventId = null,
            urgency = "normal",
            reviewAtEpochDay = null,
        ),
    )

    private val testInstructionTags = listOf(InstructionTagCrossRef(instructionId = "i1", tagId = "t1"))

    private val testPersonLinks = listOf(
        PersonLinkEntity(fromId = "p1", toId = "p2", relation = "Reports to", createdAt = "2026-08-10T10:00:00Z"),
    )

    private val testImportantDates = listOf(
        ImportantDateEntity(
            id = "d1",
            personId = "p1",
            label = "First met",
            dateEpochDay = 19560L,
            recurring = false,
            createdAt = "2026-08-10T10:00:00Z",
            updatedAt = "2026-08-10T10:00:00Z",
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(testDispatcher.asExecutor())
            .setTransactionExecutor(testDispatcher.asExecutor())
            .build()
        backupManager = BackupManager(
            context = context,
            plainExporter = PlainExporter(
                db.personDao(),
                db.instructionDao(),
                db.tagDao(),
                db.instructionTagDao(),
                db,
            ),
            personDao = db.personDao(),
            instructionDao = db.instructionDao(),
            tagDao = db.tagDao(),
            instructionTagDao = db.instructionTagDao(),
            personLinkDao = db.personLinkDao(),
            captureDao = db.captureDao(),
            importantDateDao = db.importantDateDao(),
            ftsDao = db.instructionFtsDao(),
            db = db,
        )
    }

    @After
    fun tearDown() {
        backupManager.listBackups().forEach { it.delete() }
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun seedEverything() {
        db.subdivisionDao().saveProfile(
            SubdivisionProfile("visible", "Ambasamudram", "Tirunelveli", "K. Sundaram", now),
        )
        db.subdivisionDao().saveStation(station)
        db.subdivisionDao().saveMatter(matter)
        db.personDao().upsertAll(testPeople)
        db.subdivisionDao().savePosting(
            StaffPosting("po-1", "visible", "p1", "", "Kalakad", "Posting changed", now),
        )
        db.subdivisionDao().saveReview(
            SubdivisionReview("rv-1", "visible", "all", "Whole subdivision", "Monthly review note.", now, 1, 0),
        )
        db.tagDao().upsertAll(testTags)
        db.instructionDao().upsertAll(testInstructions)
        db.instructionTagDao().attachAll(testInstructionTags)
        db.personLinkDao().upsertAll(testPersonLinks)
        db.captureDao().upsertAll(testCaptures)
        db.importantDateDao().upsertAll(testImportantDates)
    }

    private suspend fun wipeEverything() {
        db.openHelper.writableDatabase.let { raw ->
            listOf(
                "instruction_tags", "important_date", "person_link", "captures",
                "instructions", "tags", "persons",
                "subdivision_reviews", "staff_postings", "matters", "stations", "subdivision_profile",
            ).forEach { raw.execSQL("DELETE FROM $it") }
        }
        assertEquals(0, db.personDao().snapshot().size)
    }

    @Test
    fun `backup writes every table and restore brings every row back`() = runTest {
        seedEverything()
        val file = backupManager.backup()
        assertNotNull("backup() must return a file", file)
        assertTrue("backup file must exist on disk", file.exists())
        val json = file.readText()
        listOf(
            "people", "instructions", "tags", "captures", "important_dates",
            "person_links", "instruction_tags", "schema_version", "subdivision",
        ).forEach { key ->
            assertTrue("backup must include $key", json.contains("\"$key\""))
        }
        assertEquals(
            "the manual backup schema version must be 4",
            BackupManager.SCHEMA_VERSION,
            JSONObject(json).getInt("schema_version"),
        )

        wipeEverything()
        val result = backupManager.restore(file)
        assertEquals("restore must report 2 people", 2, result.people)
        assertEquals("restore must report 1 instruction", 1, result.instructions)
        assertEquals("restore must report 1 tag", 1, result.tags)
        assertEquals("restore must report 1 instruction_tag", 1, result.instructionTags)
        assertEquals("restore must report 1 person_link", 1, result.personLinks)
        assertEquals("restore must report 1 capture", 1, result.captures)
        assertEquals("restore must report 1 important_date", 1, result.importantDates)
        assertEquals(
            "restore must report the profile, station, matter, posting and review",
            5,
            result.subdivisionRecords,
        )

        // The rows are genuinely back, not merely counted.
        val person = db.personDao().getById("p1")
        assertNotNull(person)
        assertEquals("st-1", person?.stationId)
        assertTrue("the staff classification must survive", person?.isStaff == true)
        assertEquals("Coastal beat, sand mining, night patrol", person?.responsibilities)
        assertTrue("the sensitive flag must survive", person?.isSensitive == true)

        val instruction = db.instructionDao().getById("i1")
        assertEquals("st-1", instruction?.stationId)
        assertEquals("m-1", instruction?.matterId)
        assertEquals(1799999999000L, instruction?.deadlineAtMs)
        assertEquals(1789999999000L, instruction?.dueAtMs)
        assertEquals("PERSON", instruction?.audienceKind)
        assertEquals(
            "the journal must survive with its Tamil text",
            "Called; report tomorrow. தமிழ்",
            InstructionJournal.decode(instruction!!.updatesJson).single().text,
        )

        assertEquals("Ambasamudram", db.subdivisionDao().profile("visible")?.name)
        assertEquals(
            "the station notes must keep their blank line and comma",
            station.notes,
            db.subdivisionDao().station("st-1")?.notes,
        )
        assertEquals(
            "the matter description must keep its quotes and Tamil text",
            matter.description,
            db.subdivisionDao().matter("m-1")?.description,
        )
        assertEquals(1, db.subdivisionDao().postingsFor("visible", "p1").size)
        assertEquals("Whole subdivision", db.subdivisionDao().latestReview("visible", "all")?.scopeTitle)
    }

    @Test
    fun `restored instructions are findable by full-text search`() = runTest {
        seedEverything()
        val file = backupManager.backup()
        wipeEverything()
        backupManager.restore(file)
        val hits = db.instructionFtsDao().searchOnce("FIR*")
        assertEquals(
            "a restored instruction must be searchable immediately, not after a reseed",
            listOf("i1"),
            hits.map { it.id },
        )
    }

    @Test
    fun `restoring the same backup twice keeps child rows and stays idempotent`() = runTest {
        seedEverything()
        val file = backupManager.backup()
        backupManager.restore(file)
        backupManager.restore(file)
        assertEquals("no duplicate contacts", 2, db.personDao().snapshot().size)
        assertEquals("no duplicate instructions", 1, db.instructionDao().snapshot().size)
        // The pre-2.6.0 restore used INSERT OR REPLACE, which deleted the person and the
        // instruction first and cascaded these two away.
        assertEquals(
            "the label link must survive a repeated restore",
            1,
            db.instructionTagDao().snapshotAll().size,
        )
        assertEquals(
            "the contact's important dates must survive a repeated restore",
            1,
            db.importantDateDao().snapshot().size,
        )
        assertEquals(
            "the contact's relationships must survive a repeated restore",
            1,
            db.personLinkDao().snapshot().size,
        )
    }

    @Test
    fun `a backup from a newer schema version is refused and changes nothing`() = runTest {
        seedEverything()
        val file = backupManager.backup()
        val bumped = JSONObject(file.readText()).put("schema_version", BackupManager.SCHEMA_VERSION + 1)
        file.writeText(bumped.toString())
        wipeEverything()

        val failure = runCatching { backupManager.restore(file) }.exceptionOrNull()
        assertNotNull("a newer backup format must be refused", failure)
        assertTrue(
            "the message must tell the officer to update the app; got ${failure?.message}",
            failure?.message?.contains("newer version") == true,
        )
        assertEquals("the database must be untouched", 0, db.personDao().snapshot().size)
    }

    @Test
    fun `a backup with a dangling matter reference is refused and changes nothing`() = runTest {
        seedEverything()
        val file = backupManager.backup()
        val root = JSONObject(file.readText())
        // Point the instruction at a matter nobody has.
        root.getJSONArray("instructions").getJSONObject(0).put("matter_id", "m-does-not-exist")
        file.writeText(root.toString())
        wipeEverything()

        val failure = runCatching { backupManager.restore(file) }.exceptionOrNull()
        assertNotNull("a dangling matter reference must be refused", failure)
        assertTrue(
            "the message must name the problem; got ${failure?.message}",
            failure?.message?.contains("matter") == true,
        )
        assertEquals("no contact may be written", 0, db.personDao().snapshot().size)
        assertEquals("no instruction may be written", 0, db.instructionDao().snapshot().size)
        assertEquals("no station may be written", 0, db.subdivisionDao().stations().size)
    }

    @Test
    fun `a backup with a malformed instruction is refused rather than partially restored`() = runTest {
        seedEverything()
        val file = backupManager.backup()
        val root = JSONObject(file.readText())
        root.getJSONArray("instructions").getJSONObject(0).remove("status")
        file.writeText(root.toString())
        wipeEverything()

        val failure = runCatching { backupManager.restore(file) }.exceptionOrNull()
        assertNotNull("a malformed row must be refused, not skipped", failure)
        assertEquals(
            "the pre-2.6.0 parser skipped the bad row and restored the rest; that silent loss is gone",
            0,
            db.personDao().snapshot().size,
        )
    }

    @Test
    fun `a schema 3 backup restores without inventing a subdivision or staff`() = runTest {
        seedEverything()
        val file = backupManager.backup()
        // Rewrite the file as a genuine pre-2.6.0 backup: version 3, no subdivision object,
        // and none of the columns v2.6.0 added.
        val root = JSONObject(file.readText())
        root.put("schema_version", 3)
        root.remove("subdivision")
        val people = root.getJSONArray("people")
        for (i in 0 until people.length()) {
            people.getJSONObject(i).apply {
                remove("station_id"); remove("is_staff"); remove("staff_active"); remove("responsibilities")
            }
        }
        val instructions = root.getJSONArray("instructions")
        for (i in 0 until instructions.length()) {
            instructions.getJSONObject(i).apply { remove("station_id"); remove("matter_id") }
        }
        val legacy = File(file.parentFile, "kaavalan-note-backup-legacy.json")
        legacy.writeText(root.toString())
        wipeEverything()

        val result = backupManager.restore(legacy)
        assertEquals("the old backup's contacts must come back", 2, result.people)
        assertEquals("an old backup carries no subdivision records", 0, result.subdivisionRecords)
        assertNull(
            "restoring an old backup must not invent a subdivision profile",
            db.subdivisionDao().profile("visible"),
        )
        val person = db.personDao().getById("p1")
        assertFalse(
            "restoring an old backup must not classify a real person as staff",
            person?.isStaff == true,
        )
        // The free-text station name is safe to fold into a real station; the classification
        // is not.
        assertNotNull("a legacy station name should be derived into a station", person?.stationId)
        assertEquals(
            "Kalakad",
            db.subdivisionDao().station(person!!.stationId!!)?.name,
        )
        legacy.delete()
    }

    @Test
    fun `backup file name uses the timestamp pattern and lives in the backups subdirectory`() = runTest {
        val file = backupManager.backup()
        val name = file.name
        assertTrue(
            "filename must start with kaavalan-note-backup-; got $name",
            name.startsWith("kaavalan-note-backup-"),
        )
        assertTrue("filename must end with .json; got $name", name.endsWith(".json"))
        assertTrue(
            "backup file must live in the backups/ subdir; got ${file.parentFile?.name}",
            file.parentFile?.name == "backups",
        )
    }

    @Test
    fun `backup prunes old files beyond the retention limit`() = runTest {
        repeat(BackupManager.MAX_BACKUPS + 2) {
            backupManager.backup()
            // The timestamp has second resolution, so a short sleep guarantees a unique name.
            Thread.sleep(1100)
        }
        val remaining = backupManager.listBackups().size
        assertTrue(
            "after ${BackupManager.MAX_BACKUPS + 2} backups at most ${BackupManager.MAX_BACKUPS} " +
                "files may remain; got $remaining",
            remaining <= BackupManager.MAX_BACKUPS,
        )
    }
}
