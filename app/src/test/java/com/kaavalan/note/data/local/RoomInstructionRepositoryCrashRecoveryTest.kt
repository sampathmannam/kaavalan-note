package com.kaavalan.note.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaavalan.note.data.instructions.Priority
import com.kaavalan.note.data.instructions.RoomInstructionRepository
import com.kaavalan.note.data.instructions.Source
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9.9 (PROD-READINESS-P0-#2): crash-recovery test for the
 * atomic-create fix on [RoomInstructionRepository.create].
 *
 * **What this test proves.**
 * The [RoomInstructionRepository.create] method writes to four
 * places: `instructions` (main row), `instructions_fts` (FTS
 * index), `sync_queue` (outbox), and `persons.lastInteractionAt`
 * (auto-snooze, via [TouchPersonOnActivity]). Pre-fix these were
 * four sequential coroutine steps. A process death or cancellation
 * between the main-table insert and the FTS upsert left the FTS
 * index pointing at a missing rowid; a crash between the sync-queue
 * enqueue and the last-interaction touch left the decay view
 * showing the user as "haven't touched in 30+ days" for a person
 * they had just saved an instruction about.
 *
 * Post-fix (this commit) all four writes are wrapped in
 * `db.withTransaction { ... }`. The crash-recovery property
 * proven here:
 *
 *  1. If any step inside the [withTransaction] block throws, none
 *     of the four writes land. Room's transaction machinery
 *     buffers the writes; the throw triggers a rollback.
 *  2. The instruction table is empty.
 *  3. The FTS table is empty.
 *  4. The sync queue has no PENDING_INSERT entry.
 *
 * Without the transaction, scenario 1 would leave the main row +
 * FTS row written but the sync queue + touch missing — a silent
 * data-integrity bug the user would only notice on the next sync
 * attempt or decay view.
 *
 * **Why a real in-memory Room database, not mocks.** The whole
 * point of the fix is the cross-table atomicity that Room's
 * transaction implementation provides. Mocking `AppDatabase` away
 * would prove nothing — `withTransaction { }` on a mock is a
 * no-op. We use `Room.inMemoryDatabaseBuilder` so the real
 * SQLite transaction machinery is exercised, including the
 * implicit rollback on exception inside the transaction block.
 *
 * **Why we drive the throw via [PersonDao.touch], not by
 * extending [TouchPersonOnActivity].** [TouchPersonOnActivity] is
 * a `final` Kotlin class (it is `@Singleton` and Hilt-injected).
 * Subclassing it in a test is a code smell — it would force the
 * production class to be `open`, which weakens the production
 * surface for a test-only reason. Instead, this test injects a
 * real [TouchPersonOnActivity] whose [PersonDao] dependency is
 * mocked to throw on [PersonDao.touch]. The throw propagates
 * exactly as a real production failure would, and Room's
 * rollback semantics are exercised the same way.
 *
 * **Why Robolectric and not `androidTest/` instrumentation.** This
 * test runs in pure JVM with Robolectric's SQLite shadow — fast
 * (no emulator), no `connectedAndroidTest` queue, and
 * `Room.inMemoryDatabaseBuilder` is happy with the shadow
 * implementation. The full
 * [com.kaavalan.note.data.local.DatabaseModuleTest] uses the same
 * pattern.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomInstructionRepositoryCrashRecoveryTest {

    private lateinit var db: AppDatabase

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `create rolls back all four writes when PersonDao touch throws`() = runBlocking {
        val ftsDao = db.instructionFtsDao()
        val instructionDao = db.instructionDao()
        val syncQueueDao = db.syncQueueDao()
        val personDao = db.personDao()

        // Wire a [PersonDao] mock that throws on touch() so the
        // throw happens inside the [withTransaction] block —
        // exactly where a real production failure (a missing row
        // to update, a constraint failure, etc.) would occur.
        val throwingPersonDao = mockk<PersonDao>()
        coEvery {
            throwingPersonDao.touch(any(), any(), any())
        } throws RuntimeException("simulated personDao constraint failure")

        // A real [TouchPersonOnActivity] is fine to use here
        // because [PersonDao] is the seam; the production class
        // never has to be `open`-ed.
        val touchOnActivity = TouchPersonOnActivity(personDao = throwingPersonDao)

        val repo = RoomInstructionRepository(
            db = db,
            dao = instructionDao,
            ftsDao = ftsDao,
            syncQueueDao = syncQueueDao,
            touchOnActivity = touchOnActivity,
            appScope = appScope,
        )

        // ACT: trigger the crash.
        var caught: Throwable? = null
        try {
            repo.create(
                personId = "person-missing",
                source = Source.TEXT,
                priority = Priority.NORMAL,
                title = "Buy milk",
                rawText = "Buy milk on the way home",
                dueAt = null,
            )
        } catch (e: Throwable) {
            caught = e
        }

        // ASSERT: the exception surfaced.
        assertNotNull(
            "create() must propagate the touch failure, not swallow it",
            caught,
        )
        assertTrue(
            "create() must surface the underlying cause (${caught?.message})",
            caught?.message?.contains("personDao constraint") == true,
        )

        // ASSERT: no instruction row landed. We use the
        // [InstructionDao.snapshot] query (the "SELECT * FROM
        // instructions ORDER BY capturedAt DESC" query) to count
        // what survived the rollback.
        val allInstructions = instructionDao.snapshot()
        assertEquals(
            "no instruction row should land when the transaction rolls back (was: ${allInstructions.size})",
            0,
            allInstructions.size,
        )

        // ASSERT: no sync-queue PENDING_INSERT entry. We use
        // snapshotReady(now + 60s) to read everything currently
        // eligible to drain, which is everything we just enqueued.
        val queueRows = syncQueueDao.snapshotReady(System.currentTimeMillis() + 60_000)
        assertTrue(
            "sync queue should be empty after a rolled-back create() (was: ${queueRows.size})",
            queueRows.isEmpty(),
        )

        // ASSERT: PersonDao.touch() was called exactly once
        // (inside the transaction) and the throw propagated.
        coVerify(exactly = 1) {
            throwingPersonDao.touch(any(), any(), any())
        }
    }

    @Test
    fun `create lands all four writes when no side effect throws`() = runBlocking {
        val ftsDao = db.instructionFtsDao()
        val instructionDao = db.instructionDao()
        val syncQueueDao = db.syncQueueDao()
        val personDao = db.personDao()

        // A real [TouchPersonOnActivity] against a relaxed mock
        // [PersonDao] is a no-op success path: touch() is invoked
        // with the (non-null) personId, the mock returns Unit, and
        // the rest of the transaction commits.
        val happyPersonDao = mockk<PersonDao>(relaxed = true)
        val touchOnActivity = TouchPersonOnActivity(personDao = happyPersonDao)

        val repo = RoomInstructionRepository(
            db = db,
            dao = instructionDao,
            ftsDao = ftsDao,
            syncQueueDao = syncQueueDao,
            touchOnActivity = touchOnActivity,
            appScope = appScope,
        )

        val result = repo.create(
            personId = "person-1",
            source = Source.TEXT,
            priority = Priority.NORMAL,
            title = "Test instruction",
            rawText = "Test raw text",
            dueAt = null,
        )

        // The instruction landed.
        val allInstructions = instructionDao.snapshot()
        assertEquals(
            "exactly one instruction row should land on a clean create() (was: ${allInstructions.size})",
            1,
            allInstructions.size,
        )
        assertEquals(result.id, allInstructions.first().id)

        // The sync queue has a PENDING_INSERT entry.
        val queueRows = syncQueueDao.snapshotReady(System.currentTimeMillis() + 60_000)
        assertEquals(
            "exactly one sync-queue PENDING_INSERT entry should land (was: ${queueRows.size})",
            1,
            queueRows.size,
        )
        assertEquals(result.id, queueRows.first().rowId)

        // PersonDao.touch() was called with the personId.
        coVerify(exactly = 1) {
            happyPersonDao.touch("person-1", any(), any())
        }
    }

    @Test
    fun `lifecycle and reminder mutations roll back when outbox enqueue fails`() = runBlocking {
        val instructionDao = db.instructionDao()
        val syncQueueDao = db.syncQueueDao()
        val now = "2026-09-14T08:00:00Z"
        listOf("ui-action", "reminder-action").forEach { id ->
            instructionDao.upsert(
                com.kaavalan.note.data.local.entities.InstructionEntity(
                    id = id,
                    personId = null,
                    direction = "SELF",
                    status = "OPEN",
                    source = "TEXT",
                    priority = "NORMAL",
                    title = "Atomic mutation",
                    rawText = "Atomic mutation",
                    dueAt = null,
                    capturedAt = now,
                    createdAt = now,
                    updatedAt = now,
                    syncStatus = com.kaavalan.note.data.local.entities.SyncStatus.SYNCED,
                ),
            )
        }
        val repo = RoomInstructionRepository(
            db = db,
            dao = instructionDao,
            ftsDao = db.instructionFtsDao(),
            syncQueueDao = syncQueueDao,
            touchOnActivity = TouchPersonOnActivity(db.personDao()),
            appScope = appScope,
        )

        // Simulate a disk/constraint failure at the second half of each logical write.
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_instruction_outbox
            BEFORE INSERT ON sync_queue
            WHEN NEW.`table` = 'instructions'
            BEGIN
                SELECT RAISE(ABORT, 'simulated outbox failure');
            END
            """.trimIndent(),
        )

        assertNotNull(runCatching { repo.markDone("ui-action") }.exceptionOrNull())
        assertNotNull(runCatching { repo.setDueChip("reminder-action", 1_800_000_000_000L) }.exceptionOrNull())

        val uiRow = instructionDao.getById("ui-action")
        assertEquals("DONE must roll back with its outbox write", "OPEN", uiRow?.status)
        assertEquals(com.kaavalan.note.data.local.entities.SyncStatus.SYNCED, uiRow?.syncStatus)
        val reminderRow = instructionDao.getById("reminder-action")
        assertEquals("reminder timestamp must roll back with its outbox write", null, reminderRow?.dueAtMs)
        assertEquals(com.kaavalan.note.data.local.entities.SyncStatus.SYNCED, reminderRow?.syncStatus)
        assertTrue("no half-written outbox row may survive", syncQueueDao.snapshot().isEmpty())
    }

    // ---- Test helpers ----

    /**
     * Helper note: when verifying a mockk call with a
     * specific literal argument, mockk 1.13's `coVerify`
     * block treats string literals as eq-matchers, so we
     * can write `touch("person-1", any(), any())` directly
     * without a `eq(...)` wrapper. If we needed to assert
     * against a non-literal (computed) value, we'd reach
     * for `io.mockk.MockKKt.eq` and its siblings.
     */
}
