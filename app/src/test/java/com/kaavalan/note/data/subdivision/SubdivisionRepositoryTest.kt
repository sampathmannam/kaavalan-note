package com.kaavalan.note.data.subdivision

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.PersonEntity
import com.kaavalan.note.data.vault.VaultMode
import com.kaavalan.note.data.vault.VaultModeHolder
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
 * The subdivision write boundary, against a real in-memory Room database.
 *
 * Every test here asserts one of the three rules the repository exists to hold: re-read
 * and recheck ownership at save time, one transaction per action, and archive rather than
 * destroy. Refusal messages are asserted on too, because a guard that blocks the officer
 * without saying what to do next is only half a guard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SubdivisionRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: SubdivisionRepository
    private val vault = VaultModeHolder()
    private val now = "2026-09-10T01:00:00Z"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = SubdivisionRepository(db, vault)
    }

    @After
    fun tearDown() {
        vault.reset()
        db.close()
    }

    private fun person(
        id: String,
        name: String,
        station: String? = null,
        vaultMode: String = "visible",
        sensitive: Boolean = false,
    ) = PersonEntity(
        id = id, name = name, designation = "SI", station = station, phone = "+91-9000000000",
        userId = "", createdAt = now, updatedAt = now, isSensitive = sensitive, vaultMode = vaultMode,
    )

    private fun instruction(
        id: String,
        personId: String? = null,
        status: String = "OPEN",
        stationId: String? = null,
        matterId: String? = null,
        sensitive: Boolean = false,
        audienceTarget: String? = null,
    ) = InstructionEntity(
        id = id, personId = personId, direction = "OUTGOING", status = status, source = "TEXT",
        priority = "NORMAL", title = "Title $id", rawText = "Body $id", dueAt = null,
        capturedAt = now, createdAt = now, updatedAt = now, isSensitive = sensitive,
        stationId = stationId, matterId = matterId,
        audienceKind = audienceTarget?.let { "PERSON" }, audienceTarget = audienceTarget,
    )

    private suspend fun failureFrom(block: suspend () -> Unit): String {
        val failure = runCatching { block() }.exceptionOrNull()
        assertNotNull("the action should have been refused", failure)
        return failure?.message.orEmpty()
    }

    // ---- profile ----

    @Test
    fun `a subdivision needs a name and nothing else`() = runTest {
        assertTrue(failureFrom { repo.saveProfile("  ", "", "") }.contains("Enter your subdivision name"))
        repo.saveProfile("Ambasamudram", "", "")
        val profile = db.subdivisionDao().profile("visible")
        assertEquals("Ambasamudram", profile?.name)
        assertEquals("", profile?.district)
        assertEquals("", profile?.officerName)
    }

    // ---- stations ----

    @Test
    fun `a station can be created, renamed, and keeps its stable id across the rename`() = runTest {
        val id = repo.saveStation(null, "  Kalakad  ", "Station", "Coastal beat")
        assertEquals("Kalakad", db.subdivisionDao().station(id)?.name)
        db.personDao().upsert(person("p1", "Ramesh", "Kalakad").copy(stationId = id))
        db.instructionDao().upsert(instruction("i1", "p1", stationId = id))

        val sameId = repo.saveStation(id, "Kalakad Police Station", "Station", "Coastal beat")
        assertEquals("a rename must not mint a new station", id, sameId)
        assertEquals(1, db.subdivisionDao().stations().size)
        assertEquals(
            "the contact's compatibility station text follows the rename",
            "Kalakad Police Station",
            db.personDao().getById("p1")?.station,
        )
        assertEquals(
            "historical work keeps the same stable station id after a rename",
            id,
            db.instructionDao().getById("i1")?.stationId,
        )
    }

    @Test
    fun `a duplicate trimmed name is refused, including one that differs only in ASCII case`() = runTest {
        repo.saveStation(null, "Kalakad", "Station", "")
        assertTrue(failureFrom { repo.saveStation(null, "  kalakad ", "Station", "") }.contains("already exists"))
        assertTrue(failureFrom { repo.saveStation(null, "KALAKAD", "Station", "") }.contains("already exists"))
        assertEquals(1, db.subdivisionDao().stations().size)
    }

    @Test
    fun `an archived station still owns its name, and the refusal says to reopen it`() = runTest {
        val id = repo.saveStation(null, "Kalakad", "Station", "")
        repo.archiveStation(id, true)
        val message = failureFrom { repo.saveStation(null, "Kalakad", "Station", "") }
        assertTrue(
            "uniqueness must include archived records; got: $message",
            message.contains("archived") && message.contains("Reopen"),
        )
    }

    @Test
    fun `a non-Latin station name round-trips and does not collide with a Latin one`() = runTest {
        val tamil = repo.saveStation(null, "நாங்குநேரி", "நிலையம்", "தமிழ் குறிப்பு")
        val latin = repo.saveStation(null, "Nanguneri", "Station", "")
        val saved = db.subdivisionDao().station(tamil)
        assertEquals("நாங்குநேரி", saved?.name)
        assertEquals("நிலையம்", saved?.kind)
        assertEquals("தமிழ் குறிப்பு", saved?.notes)
        assertEquals(
            "an ASCII fold must leave non-Latin characters exactly as typed",
            "நாங்குநேரி",
            saved?.nameKey,
        )
        assertTrue("the two names are different stations", tamil != latin)
        assertEquals(2, db.subdivisionDao().stations().size)
    }

    @Test
    fun `archiving a station is refused while it holds active staff, open work or active matters`() = runTest {
        val station = repo.saveStation(null, "Kalakad", "Station", "")
        db.personDao().upsert(person("p1", "Ramesh").copy(stationId = station, isStaff = true, staffActive = true))
        assertTrue(
            failureFrom { repo.archiveStation(station, true) }.contains("active staff"),
        )

        repo.saveStaff("p1", station, "Coastal beat", isStaff = true, active = false)
        db.instructionDao().upsert(instruction("i1", "p1", stationId = station))
        assertTrue(
            failureFrom { repo.archiveStation(station, true) }.contains("open instructions"),
        )

        db.instructionDao().updateExisting(db.instructionDao().getById("i1")!!.copy(status = "DONE"))
        val matter = repo.saveMatter(null, "Sand mining", station, "", "")
        assertTrue(
            failureFrom { repo.archiveStation(station, true) }.contains("active matters"),
        )

        repo.archiveMatter(matter, true)
        repo.archiveStation(station, true)
        assertTrue("with nothing blocking it, the station archives", db.subdivisionDao().station(station)!!.archived)
        assertFalse("the station is not deleted", db.subdivisionDao().stations().isEmpty())

        repo.archiveStation(station, false)
        assertFalse("reopening is unconditional", db.subdivisionDao().station(station)!!.archived)
    }

    // ---- staff ----

    @Test
    fun `marking a contact as staff records a dated posting and leaves their existing work alone`() = runTest {
        val kalakad = repo.saveStation(null, "Kalakad", "Station", "")
        val nanguneri = repo.saveStation(null, "Nanguneri", "Station", "")
        db.personDao().upsert(person("p1", "Ramesh", "Kalakad").copy(stationId = kalakad))
        db.instructionDao().upsert(instruction("i-old", "p1", stationId = kalakad, matterId = null))

        repo.saveStaff("p1", kalakad, "Coastal beat, night patrol", isStaff = true, active = true)
        val classified = db.personDao().getById("p1")!!
        assertTrue(classified.isStaff)
        assertEquals("Coastal beat, night patrol", classified.responsibilities)
        assertEquals(1, db.subdivisionDao().postingsFor("visible", "p1").size)

        repo.saveStaff("p1", nanguneri, "Town beat", isStaff = true, active = true)
        val postings = db.subdivisionDao().postingsFor("visible", "p1")
        assertEquals("each change appends; nothing is overwritten", 2, postings.size)
        val transfer = postings.first()
        assertEquals("Kalakad", transfer.fromStation)
        assertEquals("Nanguneri", transfer.toStation)
        assertEquals(
            "moving an officer must not move the work they already did",
            kalakad,
            db.instructionDao().getById("i-old")?.stationId,
        )
        assertEquals("Nanguneri", db.personDao().getById("p1")?.station)
    }

    @Test
    fun `deactivating a posting keeps the person, their responsibilities and their history`() = runTest {
        val station = repo.saveStation(null, "Kalakad", "Station", "")
        db.personDao().upsert(person("p1", "Ramesh", "Kalakad").copy(stationId = station))
        repo.saveStaff("p1", station, "Coastal beat", isStaff = true, active = true)
        repo.saveStaff("p1", station, "Coastal beat", isStaff = true, active = false)
        val person = db.personDao().getById("p1")!!
        assertTrue("an inactive posting is still a staff record", person.isStaff)
        assertFalse(person.staffActive)
        assertEquals("Coastal beat", person.responsibilities)
        assertEquals(2, db.subdivisionDao().postingsFor("visible", "p1").size)
        assertNotNull("the contact itself is never deleted", db.personDao().getById("p1"))
    }

    @Test
    fun `a contact in the other vault or marked sensitive cannot be made staff`() = runTest {
        val station = repo.saveStation(null, "Kalakad", "Station", "")
        db.personDao().upsert(person("p-hidden", "Hidden", vaultMode = "hidden"))
        db.personDao().upsert(person("p-sensitive", "Sensitive", sensitive = true))
        assertTrue(
            failureFrom { repo.saveStaff("p-hidden", station, "", isStaff = true, active = true) }
                .contains("not available in the normal subdivision workspace"),
        )
        assertTrue(
            failureFrom { repo.saveStaff("p-sensitive", station, "", isStaff = true, active = true) }
                .contains("not available in the normal subdivision workspace"),
        )
        assertTrue(
            failureFrom { repo.saveStaff("p-does-not-exist", station, "", isStaff = true, active = true) }
                .contains("no longer available"),
        )
        assertTrue("no posting may be recorded for a refused save", db.subdivisionDao().postings().isEmpty())
    }

    // ---- matters ----

    @Test
    fun `a matter with linked instructions cannot silently change station`() = runTest {
        val kalakad = repo.saveStation(null, "Kalakad", "Station", "")
        val nanguneri = repo.saveStation(null, "Nanguneri", "Station", "")
        val matter = repo.saveMatter(null, "Sand mining inquiry", kalakad, "REF/1", "Context")
        db.instructionDao().upsert(instruction("i1", stationId = kalakad, matterId = matter))

        val message = failureFrom { repo.saveMatter(matter, "Sand mining inquiry", nanguneri, "REF/1", "Context") }
        assertTrue(
            "the refusal must point at moving instructions explicitly; got: $message",
            message.contains("move individual instructions explicitly"),
        )
        assertEquals(kalakad, db.subdivisionDao().matter(matter)?.stationId)
        // Editing everything except the station is still allowed.
        repo.saveMatter(matter, "Sand mining inquiry (Phase 2)", kalakad, "REF/2", "Updated context")
        assertEquals("Sand mining inquiry (Phase 2)", db.subdivisionDao().matter(matter)?.title)
        assertEquals("REF/2", db.subdivisionDao().matter(matter)?.reference)
    }

    @Test
    fun `archiving a matter is refused while it has open work and reopening also reopens its station`() = runTest {
        val station = repo.saveStation(null, "Kalakad", "Station", "")
        val matter = repo.saveMatter(null, "Sand mining", station, "", "")
        db.personDao().upsert(person("p1", "Ramesh").copy(stationId = station))
        db.instructionDao().upsert(instruction("i1", "p1", stationId = station, matterId = matter))
        assertTrue(failureFrom { repo.archiveMatter(matter, true) }.contains("open instructions"))

        db.instructionDao().updateExisting(db.instructionDao().getById("i1")!!.copy(status = "DONE"))
        repo.archiveMatter(matter, true)
        repo.archiveStation(station, true)
        assertTrue(db.subdivisionDao().station(station)!!.archived)

        repo.archiveMatter(matter, false)
        assertFalse("a reopened matter must not be parked under an archived unit",
            db.subdivisionDao().station(station)!!.archived)
    }

    @Test
    fun `a subdivision-wide matter may span stations, a station-specific one may not`() = runTest {
        val kalakad = repo.saveStation(null, "Kalakad", "Station", "")
        val nanguneri = repo.saveStation(null, "Nanguneri", "Station", "")
        val wide = repo.saveMatter(null, "Monsoon drive", null, "", "")
        val local = repo.saveMatter(null, "Kalakad beat review", kalakad, "", "")
        db.instructionDao().upsert(instruction("i-nanguneri", stationId = nanguneri))

        repo.linkInstruction("i-nanguneri", nanguneri, wide)
        assertEquals(wide, db.instructionDao().getById("i-nanguneri")?.matterId)
        assertEquals(
            "a subdivision-wide matter does not move the recorded station",
            nanguneri,
            db.instructionDao().getById("i-nanguneri")?.stationId,
        )

        val message = failureFrom { repo.linkInstruction("i-nanguneri", nanguneri, local) }
        assertTrue(
            "a station-specific matter must reject work from another station; got: $message",
            message.contains("belongs to a different station"),
        )
    }

    // ---- work context ----

    @Test
    fun `changing the work context journals both sides and touches nothing else`() = runTest {
        val kalakad = repo.saveStation(null, "Kalakad", "Station", "")
        val nanguneri = repo.saveStation(null, "Nanguneri", "Station", "")
        val matter = repo.saveMatter(null, "Sand mining", null, "", "")
        db.personDao().upsert(person("p1", "Ramesh"))
        db.instructionDao().upsert(
            instruction("i1", "p1", stationId = kalakad).copy(dueAtMs = 111L, deadlineAtMs = 222L),
        )

        repo.linkInstruction("i1", nanguneri, matter)
        val saved = db.instructionDao().getById("i1")!!
        assertEquals(nanguneri, saved.stationId)
        assertEquals(matter, saved.matterId)
        assertEquals("responsibility is unchanged", "p1", saved.personId)
        assertEquals("the reminder is unchanged", 111L, saved.dueAtMs)
        assertEquals("the deadline is unchanged", 222L, saved.deadlineAtMs)
        val entry = com.kaavalan.note.data.instructions.InstructionJournal.decode(saved.updatesJson).single()
        assertTrue("the journal names the old context; got: ${entry.text}", entry.text.contains("Kalakad"))
        assertTrue("the journal names the new context; got: ${entry.text}", entry.text.contains("Nanguneri"))
        assertTrue("the journal names the matter; got: ${entry.text}", entry.text.contains("Sand mining"))
    }

    @Test
    fun `an unchanged context writes no journal entry`() = runTest {
        val station = repo.saveStation(null, "Kalakad", "Station", "")
        db.instructionDao().upsert(instruction("i1", stationId = station))
        repo.linkInstruction("i1", station, null)
        assertTrue(
            "re-saving the same context must not pad the history",
            com.kaavalan.note.data.instructions.InstructionJournal
                .decode(db.instructionDao().getById("i1")!!.updatesJson).isEmpty(),
        )
    }

    @Test
    fun `a sensitive record, a hidden link and an audience-only hidden link all fail closed`() = runTest {
        val station = repo.saveStation(null, "Kalakad", "Station", "")
        db.personDao().upsert(person("p-hidden", "Hidden", vaultMode = "hidden"))
        db.personDao().upsert(person("p-normal", "Normal"))
        db.instructionDao().upsert(instruction("i-sensitive", "p-normal", sensitive = true))
        db.instructionDao().upsert(instruction("i-hidden-person", "p-hidden"))
        db.instructionDao().upsert(instruction("i-audience-hidden", null, audienceTarget = "p-hidden"))
        db.instructionDao().upsert(instruction("i-dangling", "p-deleted"))

        assertTrue(
            failureFrom { repo.linkInstruction("i-sensitive", station, null) }.contains("Private instructions"),
        )
        assertTrue(
            failureFrom { repo.linkInstruction("i-hidden-person", station, null) }.contains("another workspace"),
        )
        assertTrue(
            "an audience pointer is a link too, and must not be a loophole",
            failureFrom { repo.linkInstruction("i-audience-hidden", station, null) }.contains("another workspace"),
        )
        assertTrue(
            failureFrom { repo.linkInstruction("i-dangling", station, null) }.contains("another workspace"),
        )
        assertTrue(
            failureFrom { repo.linkInstruction("i-missing", station, null) }.contains("no longer available"),
        )
        listOf("i-sensitive", "i-hidden-person", "i-audience-hidden", "i-dangling").forEach { id ->
            assertNull("nothing may be written for a refused link", db.instructionDao().getById(id)?.stationId)
        }
    }

    @Test
    fun `an archived or missing station or matter is refused with an actionable message`() = runTest {
        val station = repo.saveStation(null, "Kalakad", "Station", "")
        val matter = repo.saveMatter(null, "Sand mining", null, "", "")
        db.instructionDao().upsert(instruction("i1"))
        repo.archiveStation(station, true)
        repo.archiveMatter(matter, true)

        assertTrue(failureFrom { repo.linkInstruction("i1", station, null) }.contains("Reopen it before linking"))
        assertTrue(failureFrom { repo.linkInstruction("i1", null, matter) }.contains("Reopen it before linking"))
        assertTrue(failureFrom { repo.linkInstruction("i1", "st-nope", null) }.contains("not available"))
        assertTrue(failureFrom { repo.linkInstruction("i1", null, "m-nope") }.contains("not available"))
    }

    @Test
    fun `switching into the private workspace fails every subdivision write closed`() = runTest {
        val station = repo.saveStation(null, "Kalakad", "Station", "")
        db.personDao().upsert(person("p1", "Ramesh"))
        db.instructionDao().upsert(instruction("i1", "p1", stationId = station))

        vault.setMode(VaultMode.Hidden)
        val expected = "Return to the normal workspace"
        assertTrue(failureFrom { repo.saveProfile("Elsewhere", "", "") }.contains(expected))
        assertTrue(failureFrom { repo.saveStation(null, "Nanguneri", "Station", "") }.contains(expected))
        assertTrue(failureFrom { repo.saveStaff("p1", station, "", true, true) }.contains(expected))
        assertTrue(failureFrom { repo.saveMatter(null, "Anything", null, "", "") }.contains(expected))
        assertTrue(failureFrom { repo.archiveStation(station, true) }.contains(expected))
        assertTrue(failureFrom { repo.linkInstruction("i1", null, null) }.contains(expected))
        assertTrue(failureFrom { repo.saveReview(SubdivisionProjections.SCOPE_ALL, "Notes") }.contains(expected))

        assertNull("no profile may be written from the private workspace", db.subdivisionDao().profile("visible"))
        assertEquals("no station may be added", 1, db.subdivisionDao().stations().size)
        assertFalse("the station must not have been archived", db.subdivisionDao().station(station)!!.archived)
        assertFalse("nobody may be classified as staff", db.personDao().getById("p1")!!.isStaff)
        assertTrue("no review may be recorded", db.subdivisionDao().reviews().isEmpty())
    }

    // ---- reviews ----

    @Test
    fun `a review stores the counts that were true at save time and completes nothing`() = runTest {
        val station = repo.saveStation(null, "Kalakad", "Station", "")
        db.personDao().upsert(person("p1", "Ramesh").copy(stationId = station))
        db.instructionDao().upsert(instruction("i-open", "p1", stationId = station))
        db.instructionDao().upsert(instruction("i-ready", "p1", status = "REPORTED_DONE", stationId = station))
        db.instructionDao().upsert(instruction("i-done", "p1", status = "DONE", stationId = station))
        db.instructionDao().upsert(instruction("i-dropped", "p1", status = "DROPPED", stationId = station))

        repo.saveReview(SubdivisionProjections.SCOPE_ALL, "  Monthly review.  ")
        val review = db.subdivisionDao().latestReview("visible", SubdivisionProjections.SCOPE_ALL)!!
        assertEquals("Whole subdivision", review.scopeTitle)
        assertEquals("Monthly review.", review.notes)
        assertEquals("two open: the open one and the ready-to-verify one", 2, review.openCount)
        assertEquals(1, review.readyCount)

        assertEquals(
            "recording a review must not complete any instruction",
            "OPEN",
            db.instructionDao().getById("i-open")?.status,
        )
        assertEquals(
            "work reported complete stays reported, not verified",
            "REPORTED_DONE",
            db.instructionDao().getById("i-ready")?.status,
        )
    }

    @Test
    fun `review counts are scoped and never include hidden or sensitive records`() = runTest {
        val kalakad = repo.saveStation(null, "Kalakad", "Station", "")
        val nanguneri = repo.saveStation(null, "Nanguneri", "Station", "")
        db.personDao().upsert(person("p-ramesh", "Ramesh").copy(stationId = kalakad))
        db.personDao().upsert(person("p-kavitha", "Kavitha").copy(stationId = nanguneri))
        db.personDao().upsert(person("p-hidden", "Hidden", vaultMode = "hidden"))
        db.personDao().upsert(person("p-sensitive", "Sensitive", sensitive = true))
        db.instructionDao().upsert(instruction("i-kalakad", "p-ramesh", stationId = kalakad))
        db.instructionDao().upsert(instruction("i-nanguneri", "p-kavitha", stationId = nanguneri))
        db.instructionDao().upsert(instruction("i-hidden", "p-hidden", stationId = kalakad))
        db.instructionDao().upsert(instruction("i-sensitive-person", "p-sensitive", stationId = kalakad))
        db.instructionDao().upsert(instruction("i-sensitive-row", "p-ramesh", stationId = kalakad, sensitive = true))

        repo.saveReview(SubdivisionProjections.SCOPE_ALL, "All")
        assertEquals(
            "only the two normal-workspace records count",
            2,
            db.subdivisionDao().latestReview("visible", SubdivisionProjections.SCOPE_ALL)!!.openCount,
        )

        val stationScope = SubdivisionProjections.stationScope(kalakad)
        repo.saveReview(stationScope, "Kalakad")
        val stationReview = db.subdivisionDao().latestReview("visible", stationScope)!!
        assertEquals("Kalakad", stationReview.scopeTitle)
        assertEquals(1, stationReview.openCount)

        val officerScope = SubdivisionProjections.personScope("p-ramesh")
        repo.saveReview(officerScope, "Ramesh")
        val officerReview = db.subdivisionDao().latestReview("visible", officerScope)!!
        assertEquals("Ramesh", officerReview.scopeTitle)
        assertEquals(1, officerReview.openCount)
    }

    @Test
    fun `a review of an unavailable scope or with no note is refused`() = runTest {
        db.personDao().upsert(person("p-hidden", "Hidden", vaultMode = "hidden"))
        assertTrue(
            failureFrom { repo.saveReview(SubdivisionProjections.SCOPE_ALL, "   ") }
                .contains("Write a short note"),
        )
        assertTrue(failureFrom { repo.saveReview("nonsense", "Notes") }.contains("valid review scope"))
        assertTrue(
            failureFrom { repo.saveReview(SubdivisionProjections.stationScope("st-nope"), "Notes") }
                .contains("not available"),
        )
        assertTrue(
            failureFrom { repo.saveReview(SubdivisionProjections.personScope("p-hidden"), "Notes") }
                .contains("not available in the normal subdivision workspace"),
        )
        assertTrue(db.subdivisionDao().reviews().isEmpty())
    }

    // ---- station resolution from ordinary contact entry ----

    @Test
    fun `resolving a station from contact text reuses and reopens an archived station`() = runTest {
        val id = repo.saveStation(null, "Kalakad", "Station", "")
        repo.archiveStation(id, true)
        val resolved = SubdivisionRepository.resolveStation(db, "visible", "  kalakad ")
        assertEquals("the same station is reused rather than duplicated", id, resolved)
        assertFalse(
            "putting somebody there again reopens it rather than leaving open work under an archived unit",
            db.subdivisionDao().station(id)!!.archived,
        )
        assertEquals(1, db.subdivisionDao().stations().size)
    }

    @Test
    fun `resolving a station is vault-scoped and ignores blank text`() = runTest {
        val visible = SubdivisionRepository.resolveStation(db, "visible", "Kalakad")
        val hidden = SubdivisionRepository.resolveStation(db, "hidden", "Kalakad")
        assertTrue("the same name in the other vault is a different station", visible != hidden)
        assertNull(SubdivisionRepository.resolveStation(db, "visible", "   "))
        assertNull(SubdivisionRepository.resolveStation(db, "visible", null))
        assertEquals(2, db.subdivisionDao().stations().size)
    }
}
