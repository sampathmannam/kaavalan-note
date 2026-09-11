package com.kaavalan.note.data.subdivision

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaavalan.note.data.instructions.Direction
import com.kaavalan.note.data.instructions.InstructionJournal
import com.kaavalan.note.data.instructions.InstructionWorkflow
import com.kaavalan.note.data.instructions.Priority
import com.kaavalan.note.data.instructions.RoomInstructionRepository
import com.kaavalan.note.data.instructions.Source
import com.kaavalan.note.data.instructions.Status
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.RoomPersonRepository
import com.kaavalan.note.data.local.TouchPersonOnActivity
import com.kaavalan.note.data.reminder.ReminderManager
import com.kaavalan.note.data.vault.VaultModeHolder
import io.mockk.mockk
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.test.runTest
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
 * The whole officer flow, end to end, through the real repositories: import a contact,
 * mark them staff, create a matter, capture work in that matter's context, link existing
 * work, record progress, reassign, move the work context explicitly, verify, undo and
 * reopen.
 *
 * The assertions concentrate on the promises that are easy to break and expensive to get
 * wrong: history is appended and never replaced, a transfer does not rewrite where past
 * work happened, and a deadline is not a reminder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SubdivisionWorkflowEndToEndTest {

    private lateinit var db: AppDatabase
    private lateinit var subdivision: SubdivisionRepository
    private lateinit var people: RoomPersonRepository
    private lateinit var instructions: RoomInstructionRepository
    private lateinit var workflow: InstructionWorkflow
    private val vault = VaultModeHolder()
    private val reminders = mockk<ReminderManager>(relaxed = true)
    private val future get() = System.currentTimeMillis() + 86_400_000
    private val laterFuture get() = System.currentTimeMillis() + 172_800_000

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        subdivision = SubdivisionRepository(db, vault)
        people = RoomPersonRepository(db.personDao(), db.syncQueueDao(), db)
        instructions = RoomInstructionRepository(
            db = db,
            dao = db.instructionDao(),
            ftsDao = db.instructionFtsDao(),
            syncQueueDao = db.syncQueueDao(),
            touchOnActivity = TouchPersonOnActivity(db.personDao()),
            appScope = GlobalScope,
        )
        workflow = InstructionWorkflow(db, reminders, vault)
    }

    @After
    fun tearDown() {
        vault.reset()
        db.close()
    }

    private suspend fun capture(
        text: String,
        personId: String?,
        stationId: String? = null,
        matterId: String? = null,
        reminderAtMs: Long? = null,
    ) = instructions.createWithAudience(
        personId = personId,
        audience = null,
        source = Source.TEXT,
        priority = Priority.NORMAL,
        title = text.take(40),
        rawText = text,
        dueAt = reminderAtMs?.let { java.time.Instant.ofEpochMilli(it).toString() },
        dueAtMs = reminderAtMs,
        channel = null,
        direction = Direction.OUTGOING,
        stationId = stationId,
        matterId = matterId,
    )

    @Test
    fun `an imported contact becomes staff, and their old work keeps the station it was recorded at`() = runTest {
        // A phone-contact import carries only a name and a number.
        val imported = people.createContact("Inspector Ramesh", null, null, "+91-9000000001", "visible")
        assertEquals("+91-9000000001", db.personDao().getById(imported.id)?.phone)
        assertNull("an imported contact has no station until the officer gives them one", imported.stationId)

        val kalakad = subdivision.saveStation(null, "Kalakad", "Station", "")
        subdivision.saveStaff(imported.id, kalakad, "Coastal beat, sand mining", isStaff = true, active = true)
        val atKalakad = capture("Check the quarry road", imported.id)
        assertEquals(
            "new work snapshots the officer's current station",
            kalakad,
            db.instructionDao().getById(atKalakad.id)?.stationId,
        )

        // Transfer, then capture again.
        val nanguneri = subdivision.saveStation(null, "Nanguneri", "Station", "")
        subdivision.saveStaff(imported.id, nanguneri, "Town beat", isStaff = true, active = true)
        val atNanguneri = capture("Prepare the bandobast plan", imported.id)

        assertEquals(
            "the old instruction still belongs to Kalakad",
            kalakad,
            db.instructionDao().getById(atKalakad.id)?.stationId,
        )
        assertEquals(
            "the new instruction belongs to Nanguneri",
            nanguneri,
            db.instructionDao().getById(atNanguneri.id)?.stationId,
        )

        // Deactivating the posting must not reassign anything.
        subdivision.saveStaff(imported.id, nanguneri, "Town beat", isStaff = true, active = false)
        assertEquals(atKalakad.id, db.instructionDao().getById(atKalakad.id)?.id)
        assertEquals(imported.id, db.instructionDao().getById(atNanguneri.id)?.personId)

        // Identity and history survive: this is what a restart would read back.
        val reloaded = db.personDao().getById(imported.id)!!
        assertEquals("Inspector Ramesh", reloaded.name)
        assertEquals("+91-9000000001", reloaded.phone)
        assertEquals("Town beat", reloaded.responsibilities)
        val postings = db.subdivisionDao().postingsFor("visible", imported.id)
        assertEquals("three recorded changes: classified, transferred, deactivated", 3, postings.size)
        assertTrue(
            "the transfer entry names both stations",
            postings.any { it.fromStation == "Kalakad" && it.toStation == "Nanguneri" },
        )
    }

    @Test
    fun `capture in a matter's context is atomic, and a bad context saves nothing at all`() = runTest {
        val station = subdivision.saveStation(null, "Kalakad", "Station", "")
        val matter = subdivision.saveMatter(null, "Sand mining inquiry", station, "REF/1", "Context")
        val person = people.createContact("Ramesh", null, "Kalakad", null, "visible")

        val created = capture("Seize the two lorries", person.id, station, matter)
        val saved = db.instructionDao().getById(created.id)!!
        assertEquals(station, saved.stationId)
        assertEquals(matter, saved.matterId)
        assertEquals(
            "the returned domain object must describe what was actually saved",
            matter,
            created.matterId,
        )

        // An archived matter must abort the whole creation, not save an unlinked note.
        db.instructionDao().updateExisting(saved.copy(status = "DONE"))
        subdivision.archiveMatter(matter, true)
        val before = db.instructionDao().snapshot().size
        val failure = runCatching { capture("Second lorry seizure", person.id, station, matter) }.exceptionOrNull()
        assertNotNull("an archived matter must refuse the capture", failure)
        assertTrue(
            "the message must tell the officer to reopen it; got ${failure?.message}",
            failure?.message?.contains("Reopen") == true,
        )
        assertEquals(
            "a failed link must not leave a misleading half-saved instruction",
            before,
            db.instructionDao().snapshot().size,
        )
    }

    @Test
    fun `the full matter lifecycle appends history and never replaces it`() = runTest {
        val station = subdivision.saveStation(null, "Kalakad", "Station", "")
        val matter = subdivision.saveMatter(null, "Sand mining inquiry", station, "REF/1", "Quarry road")
        val ramesh = people.createContact("Ramesh", "SI", "Kalakad", null, "visible")
        val kavitha = people.createContact("Kavitha", "SI", "Kalakad", null, "visible")
        subdivision.saveStaff(ramesh.id, station, "Coastal beat", isStaff = true, active = true)
        subdivision.saveStaff(kavitha.id, station, "Town beat", isStaff = true, active = true)

        // 1. New work through the capture flow, in the matter's context.
        // Read the clock once: `future` recomputes on every access.
        val deadline = laterFuture
        val followUp = future
        val created = capture("Seize the two lorries", ramesh.id, station, matter, reminderAtMs = followUp)
        workflow.edit(created.id, "Seize the two lorries at the quarry road", Direction.OUTGOING, ramesh.id, deadline)

        // 2. Existing work linked in afterwards.
        val existing = capture("Collect the weighbridge records", ramesh.id)
        subdivision.linkInstruction(existing.id, station, matter)
        assertEquals(
            "both instructions are now in the matter",
            2,
            db.instructionDao().snapshot().count { it.matterId == matter },
        )

        // 3. Progress, then ready to verify.
        workflow.addUpdate(created.id, "Spoke to the tahsildar", Status.IN_PROGRESS, followUp)
        workflow.addUpdate(created.id, "Lorries seized; report tomorrow", Status.REPORTED_DONE, followUp)
        var row = db.instructionDao().getById(created.id)!!
        assertEquals(
            "reported complete is not complete: it stays open until the officer verifies",
            "REPORTED_DONE",
            row.status,
        )
        assertEquals("the deadline is independent of every follow-up", deadline, row.deadlineAtMs)
        assertEquals("the follow-up reminder is its own field", followUp, row.dueAtMs)


        // 4. Reassign responsibility. Station and matter must not move.
        workflow.edit(created.id, row.rawText, Direction.OUTGOING, kavitha.id, deadline)
        row = db.instructionDao().getById(created.id)!!
        assertEquals(kavitha.id, row.personId)
        assertEquals("reassigning the contact does not change the station", station, row.stationId)
        assertEquals("reassigning the contact does not change the matter", matter, row.matterId)
        assertTrue(
            "the journal records who it moved from and to",
            InstructionJournal.decode(row.updatesJson).last().text.let {
                it.contains("Ramesh") && it.contains("Kavitha")
            },
        )

        // 5. Explicit context change is what moves work.
        val nanguneri = subdivision.saveStation(null, "Nanguneri", "Station", "")
        val wide = subdivision.saveMatter(null, "Monsoon drive", null, "", "")
        subdivision.linkInstruction(created.id, nanguneri, wide)
        row = db.instructionDao().getById(created.id)!!
        assertEquals(nanguneri, row.stationId)
        assertEquals(wide, row.matterId)

        // 6. Verify, undo, reopen. Nothing in the history is lost along the way.
        val journalBefore = InstructionJournal.decode(row.updatesJson)
        val undo = workflow.complete(created.id)
        assertEquals("DONE", db.instructionDao().getById(created.id)?.status)
        workflow.undoCompletion(undo)
        assertEquals(
            "undo restores the progress state it interrupted",
            "REPORTED_DONE",
            db.instructionDao().getById(created.id)?.status,
        )
        workflow.complete(created.id)
        workflow.reopen(created.id)
        val finalRow = db.instructionDao().getById(created.id)!!
        assertEquals("OPEN", finalRow.status)
        assertNull(finalRow.completedAt)

        val journalAfter = InstructionJournal.decode(finalRow.updatesJson)
        assertTrue(
            "every earlier entry must still be there, in order",
            journalAfter.map { it.id }.take(journalBefore.size) == journalBefore.map { it.id },
        )
        assertTrue("the reopen is recorded", journalAfter.last().text.contains("reopened"))
        assertEquals(
            "the deadline survived the whole lifecycle",
            deadline,
            finalRow.deadlineAtMs,
        )
    }

    @Test
    fun `reopening work brings its archived station and matter back with it`() = runTest {
        val station = subdivision.saveStation(null, "Kalakad", "Station", "")
        val matter = subdivision.saveMatter(null, "Sand mining", station, "", "")
        val person = people.createContact("Ramesh", null, "Kalakad", null, "visible")
        val created = capture("Seize the lorries", person.id, station, matter)

        workflow.complete(created.id)
        subdivision.archiveMatter(matter, true)
        subdivision.archiveStation(station, true)

        workflow.reopen(created.id)
        assertFalse(
            "open work must never sit under an archived matter",
            db.subdivisionDao().matter(matter)!!.archived,
        )
        assertFalse(
            "open work must never sit under an archived station",
            db.subdivisionDao().station(station)!!.archived,
        )
    }

    @Test
    fun `undoing a completion after an intervening archive also reopens the context`() = runTest {
        val station = subdivision.saveStation(null, "Kalakad", "Station", "")
        val matter = subdivision.saveMatter(null, "Sand mining", station, "", "")
        val person = people.createContact("Ramesh", null, "Kalakad", null, "visible")
        val created = capture("Seize the lorries", person.id, station, matter)

        val undo = workflow.complete(created.id)
        // The officer tidies up in the seconds before tapping Undo on the snackbar.
        subdivision.archiveMatter(matter, true)
        subdivision.archiveStation(station, true)
        workflow.undoCompletion(undo)

        assertEquals("OPEN", db.instructionDao().getById(created.id)?.status)
        assertFalse(db.subdivisionDao().matter(matter)!!.archived)
        assertFalse(db.subdivisionDao().station(station)!!.archived)
    }

    @Test
    fun `editing a contact from the ordinary editor keeps their dates, links and posting history`() = runTest {
        val station = subdivision.saveStation(null, "Kalakad", "Station", "")
        val ramesh = people.createContact("Ramesh", "SI", "Kalakad", "+91-9000000001", "visible")
        val kavitha = people.createContact("Kavitha", "SI", "Kalakad", null, "visible")
        subdivision.saveStaff(ramesh.id, station, "Coastal beat", isStaff = true, active = true)
        db.importantDateDao().upsertAll(
            listOf(
                com.kaavalan.note.data.local.entities.ImportantDateEntity(
                    id = "d1", personId = ramesh.id, label = "First met", dateEpochDay = 19560L,
                    recurring = false, createdAt = "2026-07-01T00:00:00Z", updatedAt = "2026-07-01T00:00:00Z",
                ),
            ),
        )
        db.personLinkDao().upsertAll(
            listOf(
                com.kaavalan.note.data.local.entities.PersonLinkEntity(
                    fromId = ramesh.id, toId = kavitha.id, relation = "Reports to",
                    createdAt = "2026-07-01T00:00:00Z",
                ),
            ),
        )

        workflow.editContact(ramesh.id, "Inspector Ramesh", "Inspector", "Nanguneri", "+91-9000000002")

        val reloaded = db.personDao().getById(ramesh.id)!!
        assertEquals("Inspector Ramesh", reloaded.name)
        assertEquals("Inspector", reloaded.designation)
        assertEquals("+91-9000000002", reloaded.phone)
        assertEquals(
            "the staff classification is not lost by an ordinary contact edit",
            true,
            reloaded.isStaff,
        )
        assertEquals("Coastal beat", reloaded.responsibilities)
        assertEquals(
            "important dates cascade from persons, so a REPLACE here would have deleted this",
            1,
            db.importantDateDao().snapshot().size,
        )
        assertEquals(
            "relationships cascade from persons too",
            1,
            db.personLinkDao().snapshot().size,
        )
        assertTrue(
            "a staff member's station change from the contact editor is recorded as a posting",
            db.subdivisionDao().postingsFor("visible", ramesh.id).any { it.toStation == "Nanguneri" },
        )
        assertEquals(
            "the new station text resolved to a real station",
            "Nanguneri",
            db.subdivisionDao().station(reloaded.stationId!!)?.name,
        )
    }

    @Test
    fun `editing an instruction keeps its labels, which a REPLACE would have cascaded away`() = runTest {
        val person = people.createContact("Ramesh", null, "Kalakad", null, "visible")
        val created = capture("Seize the lorries", person.id)
        db.tagDao().upsert(
            com.kaavalan.note.data.local.entities.TagEntity(
                id = "t1", name = "urgent", kind = "FREE", color = null, usageCount = 1, lastUsedAt = null,
                userId = "", createdAt = "2026-07-01T00:00:00Z", updatedAt = "2026-07-01T00:00:00Z",
            ),
        )
        db.instructionTagDao().attachAll(
            listOf(
                com.kaavalan.note.data.local.entities.InstructionTagCrossRef(
                    instructionId = created.id, tagId = "t1",
                ),
            ),
        )

        instructions.update(created.id, Status.IN_PROGRESS, null, null, isSensitive = false)

        assertEquals(
            "the label link must survive a status change",
            1,
            db.instructionTagDao().snapshotAll().size,
        )
        assertEquals(
            "the edited note must still be findable by its own words",
            listOf(created.id),
            db.instructionFtsDao().searchOnce("lorries*").map { it.id },
        )
    }
}
