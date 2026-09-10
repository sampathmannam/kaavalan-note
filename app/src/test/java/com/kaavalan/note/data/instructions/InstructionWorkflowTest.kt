package com.kaavalan.note.data.instructions

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.PersonEntity
import com.kaavalan.note.data.reminder.ReminderManager
import com.kaavalan.note.data.vault.VaultMode
import com.kaavalan.note.data.vault.VaultModeHolder
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InstructionWorkflowTest {
    private lateinit var db: AppDatabase
    private lateinit var workflow: InstructionWorkflow
    private val reminders = mockk<ReminderManager>(relaxed = true)
    private val vault = VaultModeHolder()
    private val now = "2026-09-10T01:00:00Z"
    private val future get() = System.currentTimeMillis() + 86_400_000
    private fun row(id: String = "note", person: String? = null) = InstructionEntity(id, person, "OUTGOING", "IN_PROGRESS", "TEXT", "HIGH",
        "Original", "Original instruction", now, now, now, now, dueAtMs = 123L, deadlineAtMs = 456L)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
        workflow = InstructionWorkflow(db, reminders, vault)
    }
    @After fun close() { db.close() }

    @Test fun `update persists progress journal and reminder together without touching deadline`() = runTest {
        db.instructionDao().upsert(row())
        val at = future
        workflow.addUpdate("note", "Called inspector. தமிழ் report tomorrow", Status.REPORTED_DONE, at)
        val saved = db.instructionDao().getById("note")!!.toDomain()
        assertEquals(456L, saved.deadlineAtMs)
        assertEquals(at, saved.dueAtMs)
        assertEquals(java.time.Instant.ofEpochMilli(at).toString(), saved.dueAt)
        assertEquals(Status.REPORTED_DONE, saved.status)
        assertNull(saved.completedAt)
        assertEquals("Called inspector. தமிழ் report tomorrow", saved.updates.single().text)
        assertEquals(at, saved.updates.single().nextFollowUpAtMs)
        verify { reminders.scheduleSaved("note", at) }
    }

    @Test fun `concurrent updates append without losing either entry`() = runTest {
        db.instructionDao().upsert(row())
        (1..10).map { n -> async { workflow.addUpdate("note", "Update $n", Status.IN_PROGRESS, null) } }.awaitAll()
        assertEquals(10, db.instructionDao().getById("note")!!.toDomain().updates.map { it.text }.toSet().size)
    }

    @Test fun `edit retains identity reminder journal and other instruction FTS rows`() = runTest {
        db.instructionDao().upsert(row())
        db.instructionDao().upsert(row("other"))
        workflow.addUpdate("note", "Phone call", Status.IN_PROGRESS, null)
        workflow.edit("note", "Revised deployment", Direction.INCOMING, null, future)
        val saved = db.instructionDao().getById("note")!!
        assertEquals("note", saved.id)
        assertEquals(now, saved.capturedAt)
        assertNull(saved.dueAtMs)
        assertEquals("INCOMING", saved.direction)
        assertEquals("Original instruction", saved.toDomain().updates.last().previousText)
        assertEquals(2, saved.toDomain().updates.size)
        assertEquals(listOf("note"), db.instructionFtsDao().searchOnce("Revised*").map { it.id })
        assertEquals("Original instruction", db.instructionDao().getById("other")!!.rawText)
    }

    @Test fun `completion undo restores prior progress and reminder without replacing edited text`() = runTest {
        db.instructionDao().upsert(row())
        val undo = workflow.complete("note")
        assertEquals("DONE", db.instructionDao().getById("note")!!.status)
        workflow.edit("note", "Correction after done", Direction.SELF, null, 999L)
        workflow.undoCompletion(undo)
        val saved = db.instructionDao().getById("note")!!
        assertEquals("IN_PROGRESS", saved.status)
        assertNull(saved.completedAt)
        assertEquals("Correction after done", saved.rawText)
        assertEquals(999L, saved.deadlineAtMs)
        verify { reminders.cancelDelivery("note") }
        verify { reminders.scheduleSaved("note", 123L) }
    }

    @Test fun `stale undo cannot overwrite a newer lifecycle action`() = runTest {
        db.instructionDao().upsert(row())
        val undo = workflow.complete("note")
        db.instructionDao().updateStatus("note", "DROPPED", now, null, "No longer needed")
        assertTrue(runCatching { workflow.undoCompletion(undo) }.isFailure)
        assertEquals("DROPPED", db.instructionDao().getById("note")!!.status)
    }

    @Test fun `failed validation leaves journal status and reminder unchanged`() = runTest {
        val original = row()
        db.instructionDao().upsert(original)
        assertTrue(runCatching { workflow.addUpdate("note", "", Status.IN_PROGRESS, null) }.isFailure)
        assertTrue(runCatching { workflow.addUpdate("note", "A reply", Status.DONE, null) }.isFailure)
        assertTrue(runCatching { workflow.addUpdate("note", "A reply", Status.OPEN, 1L) }.isFailure)
        assertEquals(original, db.instructionDao().getById("note"))
    }

    @Test fun `hidden contact linkage is enforced at write time and cannot be removed`() = runTest {
        db.personDao().upsert(PersonEntity("hidden", "Inspector", "SI", "North", null, "owner", now, now, vaultMode = "hidden"))
        db.instructionDao().upsert(row(person = "hidden"))
        assertTrue(runCatching { workflow.addUpdate("note", "Must not cross vault", Status.OPEN, null) }.isFailure)
        vault.setMode(VaultMode.Hidden)
        workflow.addUpdate("note", "Private update", Status.IN_PROGRESS, null)
        assertTrue(runCatching { workflow.edit("note", "Edited", Direction.SELF, null, null) }.isFailure)
        assertEquals("hidden", db.instructionDao().getById("note")!!.personId)
        vault.setMode(VaultMode.Visible)
        assertTrue(runCatching { workflow.complete("note") }.isFailure)
    }

    @Test fun `edit contact preserves private attributes and instruction linkage`() = runTest {
        val person = PersonEntity("p", "Imported", null, null, "123", "owner", now, now, tier = "Inner")
        db.personDao().upsert(person)
        db.instructionDao().upsert(row(person = "p"))
        workflow.editContact("p", " Inspector Ravi ", " SI ", " North ", " 456 ")
        val saved = db.personDao().getById("p")!!
        assertEquals("Inspector Ravi", saved.name)
        assertEquals("SI", saved.designation)
        assertEquals("North", saved.station)
        assertEquals("456", saved.phone)
        assertEquals(person.tier, saved.tier)
        assertEquals(person.createdAt, saved.createdAt)
        assertEquals("p", db.instructionDao().getById("note")!!.personId)
    }

    @Test fun `closed and sensitive records cannot accept a progress update`() = runTest {
        db.instructionDao().upsert(row().copy(status = "DONE"))
        assertTrue(runCatching { workflow.addUpdate("note", "Reply", Status.OPEN, null) }.isFailure)
        db.instructionDao().upsert(row().copy(isSensitive = true))
        assertTrue(runCatching { workflow.addUpdate("note", "Reply", Status.OPEN, null) }.isFailure)
    }
}
