package com.kaavalan.note.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaavalan.note.data.local.entities.PersonEntity
import com.kaavalan.note.data.local.entities.SyncStatus
import com.kaavalan.note.data.person.Person
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
 * v2.0.0 (drop Supabase): tests for the local-only
 * [RoomPersonRepository]. The v1.x `write-through to Supabase
 * via SyncEngine` path is gone — `create()` and `setSensitive()`
 * are pure Room writes. The `sync_queue` table is in the schema
 * for forward-compat with a future optional cloud sync, but no
 * rows are written in v2.0.0.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomPersonRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var personDao: PersonDao
    private lateinit var syncQueueDao: SyncQueueDao
    private lateinit var repo: RoomPersonRepository

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        personDao = db.personDao()
        syncQueueDao = db.syncQueueDao()
        repo = RoomPersonRepository(
            dao = personDao,
            syncQueueDao = syncQueueDao,
            db = db,
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `create writes Room row with PENDING_INSERT and returns the domain Person`() = runTest {
        val result = repo.create(name = "Ramu", designation = "SHO", station = "Bandipora")
        advanceUntilIdle()

        // Returns the domain Person (with the client-generated id).
        assertEquals("Ramu", result.name)
        assertEquals("SHO", result.designation)
        assertEquals("Bandipora", result.station)
        // The Room row exists with PENDING_INSERT sync status.
        val rows = personDao.snapshot()
        assertEquals(1, rows.size)
        assertEquals("Ramu", rows[0].name)
        assertEquals(SyncStatus.PENDING_INSERT, rows[0].syncStatus)
        // v2.0.0: no sync_queue entry is written. The schema
        // still has the table (forward-compat) but no rows.
        assertEquals(0, syncQueueDao.snapshot().size)
    }

    @Test
    fun `create accepts an explicit clientId and reuses it`() = runTest {
        val result = repo.create(
            name = "Priya",
            designation = "DSP",
            station = "Srinagar",
            clientId = "explicit-1",
        )
        advanceUntilIdle()

        assertEquals("explicit-1", result.id)
        val rows = personDao.snapshot()
        assertEquals(1, rows.size)
        assertEquals("explicit-1", rows[0].id)
    }

    @Test
    fun `contact import retains phone and respects the selected vault`() = runTest {
        val contact = repo.createContact("Imported officer", "SI", "North", "5550100", "hidden")
        assertEquals("5550100", contact.phone)
        val stored = personDao.snapshot().single()
        assertEquals("5550100", stored.phone)
        assertEquals("hidden", stored.vaultMode)
        assertTrue(repo.observeAllInMode("visible").first().isEmpty())
        assertEquals(listOf(contact), repo.observeAllInMode("hidden").first())
    }

    @Test
    fun `observeAllInMode filters by the vault mode (visible vs hidden)`() = runTest {
        personDao.upsert(
            PersonEntity(
                id = "v-1", name = "Visible1", designation = null, station = null,
                phone = null, userId = "",
                createdAt = "2026-08-12T00:00:00Z", updatedAt = "2026-08-12T00:00:00Z",
                syncStatus = SyncStatus.SYNCED, vaultMode = "visible",
            ),
        )
        personDao.upsert(
            PersonEntity(
                id = "h-1", name = "Hidden1", designation = null, station = null,
                phone = null, userId = "",
                createdAt = "2026-08-12T00:00:00Z", updatedAt = "2026-08-12T00:00:00Z",
                syncStatus = SyncStatus.SYNCED, vaultMode = "hidden",
            ),
        )
        advanceUntilIdle()

        val visible = repo.observeAllInMode("visible").first()
        val hidden = repo.observeAllInMode("hidden").first()

        assertEquals(1, visible.size)
        assertEquals("Visible1", visible[0].name)
        assertEquals(1, hidden.size)
        assertEquals("Hidden1", hidden[0].name)
    }

    @Test
    fun `findByName returns the local Room row`() = runTest {
        repo.create(name = "LocalRamu", designation = "SHO", station = "B")
        advanceUntilIdle()

        val found = repo.findByName("LocalRamu")
        assertNotNull(found)
        assertEquals("LocalRamu", found!!.name)
        assertEquals("SHO", found.designation)
    }

    @Test
    fun `findByName returns null when not present`() = runTest {
        val found = repo.findByName("Ghost")
        assertNull(found)
    }

    @Test
    fun `findOrCreate creates when absent and returns existing when present`() = runTest {
        val first = repo.findOrCreate("NewPerson", "SHO", "B")
        advanceUntilIdle()
        assertEquals(1, personDao.snapshot().size)

        val second = repo.findOrCreate("NewPerson", "SHO", "B")
        advanceUntilIdle()
        // No duplicate row.
        assertEquals(1, personDao.snapshot().size)
        // Same id.
        assertEquals(first.id, second.id)
    }

    @Test
    fun `setSensitive flips the local row and marks SYNCED immediately (no wire push)`() = runTest {
        // v2.0.0: there's no wire push. The setSensitive call
        // updates Room and marks the row SYNCED. The v1.x
        // behaviour (PENDING_UPDATE + sync_queue + drain) is
        // gone.
        val created = repo.create(name = "Ramu", designation = "SHO", station = "B")
        advanceUntilIdle()
        // The new row starts PENDING_INSERT.
        assertEquals(SyncStatus.PENDING_INSERT, personDao.getById(created.id)!!.syncStatus)

        repo.setSensitive(created.id, true)
        advanceUntilIdle()

        val row = personDao.getById(created.id)!!
        assertTrue("isSensitive should be true", row.isSensitive)
        // v2.0.0: setSensitive immediately marks the row SYNCED
        // (no wire to wait on). The sync_queue stays empty.
        assertEquals(SyncStatus.SYNCED, row.syncStatus)
        assertEquals(0, syncQueueDao.snapshot().size)
    }

    @Test
    fun `setSensitive with non-existent id is a silent no-op`() = runTest {
        // v2.0.0: the DAO's getById returns null and the repo
        // silently returns. No exception, no row created.
        repo.setSensitive("ghost-id", true)
        advanceUntilIdle()

        assertEquals(0, personDao.snapshot().size)
        assertEquals(0, syncQueueDao.snapshot().size)
    }

    @Test
    fun `refreshFromNetwork is a no-op in v2_0_0`() = runTest {
        // v2.0.0: there's no remote. The function exists for
        // forward-compat; calling it is a no-op (no rows are
        // touched).
        repo.create(name = "Ramu", designation = "SHO", station = "B")
        advanceUntilIdle()
        val beforeCount = personDao.snapshot().size

        repo.refreshFromNetwork()
        advanceUntilIdle()

        val afterCount = personDao.snapshot().size
        assertEquals(beforeCount, afterCount)
    }
}
