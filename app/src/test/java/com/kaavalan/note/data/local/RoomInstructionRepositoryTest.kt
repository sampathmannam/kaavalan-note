package com.kaavalan.note.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaavalan.note.data.instructions.RoomInstructionRepository
import com.kaavalan.note.data.instructions.Direction
import com.kaavalan.note.data.instructions.Priority
import com.kaavalan.note.data.instructions.Source
import com.kaavalan.note.data.instructions.Status
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.SyncQueueEntity
import com.kaavalan.note.data.local.entities.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.0.0 (drop Supabase): the RoomInstructionRepository is
 * local-only. The v1.x `refreshFromNetwork(remote: SupabaseInstructionRepository)`
 * path is gone (no remote to refresh from). The lifecycle
 * mutations ([markDone], [markDropped], [reopen]) are unchanged
 * except for the no-op `syncEngine` field (kept for forward-compat
 * with the `sync_queue` table; no rows are drained in v2.0.0).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomInstructionRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: RoomInstructionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RoomInstructionRepository(
            db = db,
            dao = db.instructionDao(),
            ftsDao = db.instructionFtsDao(),
            syncQueueDao = db.syncQueueDao(),
            touchOnActivity = TouchPersonOnActivity(db.personDao()),
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * v1.1: markDone sets status=DONE, completedAt=now, refreshes
     * updatedAt (so the brief's 7-day window resets), and writes
     * PENDING_UPDATE so a future v2.x cloud-sync drain can PATCH
     * the server.
     */
    @Test
    fun `markDone transitions to DONE with completedAt and queues PENDING_UPDATE`() = runBlocking {
        seed("ins-1")
        repo.markDone("ins-1")

        val saved = db.instructionDao().getById("ins-1")
        assertEquals(Status.DONE.name, saved?.status)
        assertNotNull(saved?.completedAt)
        assertEquals(SyncStatus.PENDING_UPDATE, saved?.syncStatus)
        val queued = db.syncQueueDao().snapshot()
        assertEquals(1, queued.size)
        assertEquals("ins-1", queued.single().rowId)
        assertEquals(SyncQueueEntity.OP_UPDATE, queued.single().op)
    }

    /**
     * v1.1: markDropped sets status=DROPPED, droppedReason, and
     * refreshes updatedAt. The row stays in Room (the spec's
     * silent drop is the carriedOver > 30 days rule).
     */
    @Test
    fun `markDropped transitions to DROPPED with reason`() = runBlocking {
        seed("ins-2")
        repo.markDropped("ins-2", "Already handled offline")

        val saved = db.instructionDao().getById("ins-2")
        assertEquals(Status.DROPPED.name, saved?.status)
        assertEquals("Already handled offline", saved?.droppedReason)
        assertEquals(SyncStatus.PENDING_UPDATE, saved?.syncStatus)
        assertEquals("ins-2", db.syncQueueDao().snapshot().single().rowId)
    }

    /**
     * v1.1: re-open clears completedAt/droppedReason and resets
     * status to OPEN. The 7-day brief window restarts.
     */
    @Test
    fun `reopen clears lifecycle fields and resets status to OPEN`() = runBlocking {
        seed(
            id = "ins-3",
            status = Status.DONE.name,
            completedAt = "2026-09-14T08:00:00Z",
            droppedReason = "old reason",
        )
        repo.reopen("ins-3")

        val saved = db.instructionDao().getById("ins-3")
        assertEquals(Status.OPEN.name, saved?.status)
        assertEquals(null, saved?.completedAt)
        assertEquals(null, saved?.droppedReason)
        assertEquals(SyncStatus.PENDING_UPDATE, saved?.syncStatus)
        assertEquals("ins-3", db.syncQueueDao().snapshot().single().rowId)
    }

    @Test
    fun `for-me capture preserves who assigned it as a historical label`() = runBlocking {
        val created = repo.createWithAudience(
            personId = null,
            audience = null,
            source = Source.TEXT,
            priority = Priority.NORMAL,
            title = "Review procession plan",
            rawText = "Review procession plan",
            dueAt = null,
            dueAtMs = null,
            channel = null,
            direction = Direction.SELF,
            assignedByLabel = "DIG",
        )

        assertEquals("DIG", created.assignedByLabel)
        assertEquals("DIG", db.instructionDao().getById(created.id)?.assignedByLabel)
    }

    private suspend fun seed(
        id: String,
        status: String = Status.OPEN.name,
        completedAt: String? = null,
        droppedReason: String? = null,
    ) {
        val now = "2026-09-14T08:00:00Z"
        db.instructionDao().upsert(
            InstructionEntity(
                id = id,
                personId = null,
                direction = "SELF",
                status = status,
                source = "TEXT",
                priority = "NORMAL",
                title = "Atomic note",
                rawText = "Atomic note",
                dueAt = null,
                capturedAt = now,
                createdAt = now,
                updatedAt = now,
                completedAt = completedAt,
                droppedReason = droppedReason,
                syncStatus = SyncStatus.SYNCED,
            ),
        )
    }
}
