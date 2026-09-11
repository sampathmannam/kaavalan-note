package com.kaavalan.note.ui.subdivision

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.RoomPersonRepository
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.subdivision.SubdivisionProjections
import com.kaavalan.note.data.subdivision.SubdivisionRepository
import com.kaavalan.note.data.vault.VaultMode
import com.kaavalan.note.data.vault.VaultModeHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * The subdivision read model and its mutation surface.
 *
 * The two behaviours worth defending here are the ones a screenshot would not catch: a
 * vault switch must reset the state before it can reveal anything, and a refused save
 * must surface the repository's own actionable sentence rather than a generic apology.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SubdivisionViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var viewModel: SubdivisionViewModel
    private val vault = VaultModeHolder()
    private val now = "2026-09-10T09:00:00Z"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor(testDispatcher.asExecutor())
            .setTransactionExecutor(testDispatcher.asExecutor())
            .build()
        viewModel = SubdivisionViewModel(
            repository = SubdivisionRepository(db, vault),
            subdivisionDao = db.subdivisionDao(),
            people = RoomPersonRepository(db.personDao(), db.syncQueueDao(), db),
            instructionDao = db.instructionDao(),
            vault = vault,
        )
    }

    @After
    fun tearDown() {
        vault.reset()
        db.close()
        Dispatchers.resetMain()
    }

    private fun instruction(
        id: String,
        personId: String?,
        sensitive: Boolean = false,
        status: String = "OPEN",
    ) = InstructionEntity(
        id = id, personId = personId, direction = "OUTGOING", status = status, source = "TEXT",
        priority = "NORMAL", title = "Title $id", rawText = "Body $id", dueAt = null,
        capturedAt = now, createdAt = now, updatedAt = now, isSensitive = sensitive,
    )

    @Test
    fun `the state loads the officer's own record`() = runTest {
        viewModel.saveProfile("Ambasamudram", "Tirunelveli", "K. S.") {}
        viewModel.saveStation(null, "Kalakad", "Station", "") {}
        val state = viewModel.state.first { !it.loading }
        assertEquals("Ambasamudram", state.profile?.name)
        assertEquals(listOf("Kalakad"), state.stations.map { it.name })
        assertTrue(state.ready)
        assertTrue(state.isConfigured)
        assertNull(state.error)
    }

    @Test
    fun `an unconfigured subdivision is a valid, ready state rather than an error`() = runTest {
        val state = viewModel.state.first { !it.loading }
        assertTrue("nothing has gone wrong; the officer simply has not set up yet", state.ready)
        assertFalse(state.isConfigured)
        assertNull(state.profile)
        assertNull(state.error)
    }

    @Test
    fun `switching to the private workspace marks the record unavailable and empties it`() = runTest {
        viewModel.saveProfile("Ambasamudram", "", "") {}
        viewModel.saveStation(null, "Kalakad", "Station", "") {}
        assertEquals(1, viewModel.state.first { it.stations.isNotEmpty() }.stations.size)

        vault.setMode(VaultMode.Hidden)
        val hidden = viewModel.state.first { !it.available }
        assertFalse("the CRM is a normal-workspace feature in this version", hidden.available)
        assertFalse("an unavailable state is not a loading state", hidden.loading)
        assertFalse("`ready` must be false so no screen renders content", hidden.ready)
        assertNull(
            "not one row of the normal workspace may survive the switch",
            hidden.profile,
        )
        assertTrue(hidden.stations.isEmpty())
        assertTrue(hidden.matters.isEmpty())
        assertTrue(hidden.contacts.isEmpty())
        assertTrue(hidden.instructions.isEmpty())

        vault.setMode(VaultMode.Visible)
        assertEquals(
            "switching back restores the officer's own record",
            "Ambasamudram",
            viewModel.state.first { it.available && it.profile != null }.profile?.name,
        )
    }

    @Test
    fun `the projected work list excludes sensitive rows and contacts from the other vault`() = runTest {
        val people = RoomPersonRepository(db.personDao(), db.syncQueueDao(), db)
        val normal = people.createContact("Ramesh", null, "Kalakad", null, "visible")
        val hidden = people.createContact("Hidden", null, null, null, "hidden")
        people.createContact("Sensitive", null, null, null, "visible").also {
            people.setSensitive(it.id, true)
        }
        db.instructionDao().upsert(instruction("i-visible", normal.id))
        db.instructionDao().upsert(instruction("i-hidden", hidden.id))
        db.instructionDao().upsert(instruction("i-sensitive-row", normal.id, sensitive = true))
        db.instructionDao().upsert(instruction("i-unassigned", null))

        val state = viewModel.state.first { it.instructions.isNotEmpty() }
        assertEquals(
            "only the normal-workspace records reach the CRM projection",
            setOf("i-visible", "i-unassigned"),
            state.instructions.map { it.id }.toSet(),
        )
        assertEquals(
            "a sensitive contact is not offered as staff or as a review scope",
            listOf("Ramesh"),
            state.contacts.map { it.name },
        )
    }

    @Test
    fun `a refused save surfaces the repository's own actionable sentence`() = runTest {
        var saved = false
        viewModel.saveStation(null, "  ", "Station", "") { saved = true }
        val message = viewModel.mutationError.first { it != null }
        assertFalse("the caller's success callback must not fire", saved)
        assertTrue(
            "the officer must be told what to do, not merely that something failed; got: $message",
            message?.contains("Enter a station or unit name") == true,
        )
        assertFalse("the busy flag must be released", viewModel.busy.first())

        viewModel.dismissError()
        assertNull(viewModel.mutationError.first())
    }

    @Test
    fun `an archive blocked by open work reports which blocker it was`() = runTest {
        var stationId = ""
        viewModel.saveStation(null, "Kalakad", "Station", "") { stationId = it }
        val people = RoomPersonRepository(db.personDao(), db.syncQueueDao(), db)
        val person = people.createContact("Ramesh", null, "Kalakad", null, "visible")
        db.instructionDao().upsert(instruction("i1", person.id).copy(stationId = stationId))

        viewModel.archiveStation(stationId, true)
        val message = viewModel.mutationError.first { it != null }
        assertTrue(
            "the message must name the specific blocker; got: $message",
            message?.contains("open instructions") == true,
        )
        assertFalse(
            "nothing may have been archived",
            db.subdivisionDao().station(stationId)!!.archived,
        )
    }

    @Test
    fun `recording a review stores the counts and reports success`() = runTest {
        val people = RoomPersonRepository(db.personDao(), db.syncQueueDao(), db)
        val person = people.createContact("Ramesh", null, "Kalakad", null, "visible")
        db.instructionDao().upsert(instruction("i-open", person.id))
        db.instructionDao().upsert(instruction("i-ready", person.id, status = "REPORTED_DONE"))
        db.instructionDao().upsert(instruction("i-done", person.id, status = "DONE"))

        var done = false
        viewModel.recordReview(SubdivisionProjections.SCOPE_ALL, "Monthly review.") { done = true }
        assertTrue(done)
        assertNull(viewModel.mutationError.first())
        val review = viewModel.state.first { it.reviews.isNotEmpty() }.reviews.single()
        assertEquals(2, review.openCount)
        assertEquals(1, review.readyCount)
        assertEquals("Whole subdivision", review.scopeTitle)
    }

    @Test
    fun `retry clears a previous refusal`() = runTest {
        viewModel.saveStation(null, "", "Station", "") {}
        assertNotNull(viewModel.mutationError.first { it != null })
        viewModel.retry()
        assertNull(viewModel.mutationError.first())
    }
}
