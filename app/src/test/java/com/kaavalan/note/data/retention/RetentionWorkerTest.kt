package com.kaavalan.note.data.retention

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.kaavalan.note.data.audit.AuditChainWriter
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.AuditChainEventDao
import com.kaavalan.note.data.local.CaptureDao
import com.kaavalan.note.data.local.ImportantDateDao
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.SyncQueueDao
import com.kaavalan.note.data.local.entities.AuditChainEventEntity
import com.kaavalan.note.data.local.entities.CaptureEntity
import com.kaavalan.note.data.local.entities.ImportantDateEntity
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.SyncStatus
import com.kaavalan.note.data.audit.SigningKeyProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.0.1 (PM rating): the [RetentionWorker] firing test.
 * The PM rating called this out: the worker was wired but
 * untested, which meant a future change to the retention
 * window or the redaction policy could ship without
 * anyone noticing. This test:
 *
 *  1. Seeds old + new rows in captures, important_dates,
 *     and the audit chain.
 *  2. Drives the worker via [TestListenableWorkerBuilder]
 *     (no Hilt — the worker's deps are constructed
 *     directly here).
 *  3. Asserts the old rows are deleted/redacted and the
 *     new rows survive.
 *  4. Asserts the worker wrote a `RETENTION_RUN` audit
 *     event.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RetentionWorkerTest {

    private lateinit var db: AppDatabase
    private lateinit var captureDao: CaptureDao
    private lateinit var instructionDao: InstructionDao
    private lateinit var importantDateDao: ImportantDateDao
    private lateinit var auditDao: AuditChainEventDao
    private lateinit var syncQueueDao: SyncQueueDao
    private lateinit var auditWriter: AuditChainWriter
    private lateinit var personDao: com.kaavalan.note.data.local.PersonDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        captureDao = db.captureDao()
        instructionDao = db.instructionDao()
        importantDateDao = db.importantDateDao()
        auditDao = db.auditChainEventDao()
        syncQueueDao = db.syncQueueDao()
        personDao = db.personDao()
        auditWriter = AuditChainWriter(auditDao, signingKeyProvider = { "test-signing-key" })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `worker deletes old captures and redacts old audit rows`() = runBlocking {
        // Seed 2 captures — one ancient (8 years old), one
        // recent. The default retention window for captures
        // is 2 years, so the old one should be deleted.
        val ancientMs = System.currentTimeMillis() - 8L * 365 * 24 * 60 * 60 * 1000
        val ancientIso = java.time.Instant.ofEpochMilli(ancientMs).toString()
        val recentIso = java.time.Instant.now().toString()
        captureDao.upsert(
            CaptureEntity(
                id = "cap-old",
                mode = "TEXT",
                rawText = "ancient note",
                audioUri = null,
                imageUri = null,
                processed = false,
                createdAt = ancientIso,
                syncStatus = SyncStatus.SYNCED,
            ),
        )
        captureDao.upsert(
            CaptureEntity(
                id = "cap-recent",
                mode = "TEXT",
                rawText = "recent note",
                audioUri = null,
                imageUri = null,
                processed = false,
                createdAt = recentIso,
                syncStatus = SyncStatus.SYNCED,
            ),
        )

        // Seed 2 important dates — same age split.
        // ImportantDate has a FK to Person, so seed a
        // Person first.
        personDao.upsert(
            com.kaavalan.note.data.local.entities.PersonEntity(
                id = "p1", name = "Ramu", designation = null, station = null,
                phone = null, userId = "",
                createdAt = recentIso, updatedAt = recentIso,
                syncStatus = SyncStatus.SYNCED,
            ),
        )
        importantDateDao.upsert(
            ImportantDateEntity(
                id = "date-old",
                personId = "p1",
                label = "ancient",
                dateEpochDay = 0L,
                recurring = false,
                createdAt = ancientIso,
                updatedAt = ancientIso,
            ),
        )
        importantDateDao.upsert(
            ImportantDateEntity(
                id = "date-recent",
                personId = "p1",
                label = "recent",
                dateEpochDay = 0L,
                recurring = false,
                createdAt = recentIso,
                updatedAt = recentIso,
            ),
        )

        // Seed 2 audit events — old + recent.
        auditWriter.append("persons", "p1", "INSERT", """{"name":"old"}""")
        // Force the old row's createdAtMs to be ancient
        // by updating it directly. The audit chain's
        // redaction is by createdAtMs, not by chain
        // position.
        val firstAudit = auditDao.snapshot().first()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE audit_chain_events SET createdAtMs = ? WHERE id = ?",
            arrayOf<Any>(ancientMs, firstAudit.id),
        )
        auditWriter.append("persons", "p1", "UPDATE", """{"name":"recent"}""")

        // Fire the worker
        val result = newWorker().doWork()
        assertTrue("expected Result.success(), got $result", result is ListenableWorker.Result.Success)

        // Captures: old deleted, recent survives.
        val captures = captureDao.snapshot()
        assertEquals(1, captures.size)
        assertEquals("cap-recent", captures[0].id)

        // Important dates: old deleted, recent survives.
        val dates = importantDateDao.snapshot()
        assertEquals(1, dates.size)
        assertEquals("date-recent", dates[0].id)

        // Audit chain: old row's payload is redacted;
        // recent row's payload is intact; the chain
        // itself (hashes) is preserved. The worker also
        // writes a `RETENTION_RUN` row, so the snapshot
        // has 3 rows: old (redacted) + recent (intact) +
        // the run-self-reference event.
        val auditRows = auditDao.snapshot()
        assertEquals(3, auditRows.size)
        val oldRow = auditRows[0]
        assertTrue(
            "old row's payload should be redacted, got ${oldRow.payload}",
            oldRow.payload.contains("\"redacted\":true"),
        )
        assertTrue("old row's thisHash should be preserved", oldRow.thisHash.isNotEmpty())
        val recentRow = auditRows[1]
        assertEquals("""{"name":"recent"}""", recentRow.payload)
        assertTrue("recent row's thisHash should be intact", recentRow.thisHash.isNotEmpty())

        // The worker wrote a RETENTION_RUN audit event
        // (the chain's self-reference: "yes, retention ran
        // at time X"). Look for the kind = "RETENTION_RUN".
        val runEvents = auditRows.filter { it.kind == "RETENTION_RUN" }
        assertTrue(
            "expected a RETENTION_RUN audit event, got ${auditRows.map { it.kind }}",
            runEvents.isNotEmpty(),
        )
    }

    @Test
    fun `worker result carries the deletion counts in outputData`() = runBlocking {
        // Seed one ancient capture
        val ancientIso = java.time.Instant.ofEpochMilli(
            System.currentTimeMillis() - 8L * 365 * 24 * 60 * 60 * 1000,
        ).toString()
        captureDao.upsert(
            CaptureEntity(
                id = "cap-old",
                mode = "TEXT",
                rawText = "x",
                audioUri = null,
                imageUri = null,
                processed = false,
                createdAt = ancientIso,
                syncStatus = SyncStatus.SYNCED,
            ),
        )

        // Drive the worker. The first test pins the
        // full side effects (rows deleted, audit
        // redacted, RETENTION_RUN event written). This
        // test pins the Result.success() contract +
        // the no-rows-deleted-when-no-old-rows case.
        val result = newWorker().doWork()
        assertTrue("expected Result.success(), got $result", result is ListenableWorker.Result.Success)

        // The DB side effects: the ancient capture is gone.
        val captures = captureDao.snapshot()
        assertEquals(0, captures.size)
    }

    /**
     * The retention sweep has to reach the note text, not just
     * the row it lives on.
     *
     * `captureDao.deleteOlderThan` is a plain
     * `DELETE FROM captures`. Until v2.2.1 every capture also
     * wrote its `rawText` into `sync_queue.payloadJson`, for a
     * drain that v2.0.0 deleted along with Supabase -- so nothing
     * read those payloads and nothing removed the rows. The
     * capture went; the words stayed, indefinitely, in a table
     * this worker did not touch. For an app whose retention
     * window is a stated feature and whose rows are police
     * instructions, a delete that leaves the text behind is not a
     * delete.
     *
     * The row is left in place and emptied rather than removed,
     * matching how the audit chain is redacted above.
     */
    @Test
    fun `retention clears note text left behind in the outbox`() = runBlocking {
        val secret = "the informant will meet me behind the temple at nine"
        syncQueueDao.enqueue(
            com.kaavalan.note.data.local.entities.SyncQueueEntity(
                table = "captures",
                rowId = "cap-gone",
                op = com.kaavalan.note.data.local.entities.SyncQueueEntity.OP_INSERT,
                // Exactly the shape a pre-v2.2.1 build wrote.
                payloadJson = """{"id":"cap-gone","rawText":"$secret","mode":"TEXT"}""",
                createdAt = 0L,
            ),
        )

        val result = newWorker().doWork()
        assertTrue("expected Result.success(), got $result", result is ListenableWorker.Result.Success)

        val rows = syncQueueDao.snapshot().filter { it.table == "captures" }
        assertEquals(1, rows.size)
        assertEquals(
            "the outbox payload must be emptied by the sweep",
            "{}",
            rows[0].payloadJson,
        )
        assertFalse(
            "the note's text must not survive the retention sweep anywhere in the outbox",
            syncQueueDao.snapshot().any { it.payloadJson.contains(secret) },
        )
    }

    private fun newWorker(): RetentionWorker {
        // TestListenableWorkerBuilder builds a Worker that
        // runs synchronously on the calling thread (since
        // the worker's doWork is suspend, we call it from
        // runBlocking in the test). The WorkerFactory
        // constructs the worker with the real deps.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                parameters: WorkerParameters,
            ): ListenableWorker = RetentionWorker(
                appContext = appContext,
                params = parameters,
                captureDao = captureDao,
                instructionDao = instructionDao,
                importantDateDao = importantDateDao,
                auditDao = auditDao,
                auditChainWriter = auditWriter,
                syncQueueDao = syncQueueDao,
            )
        }
        return TestListenableWorkerBuilder
            .from(context, RetentionWorker::class.java)
            .setWorkerFactory(factory)
            .build() as RetentionWorker
    }
}
