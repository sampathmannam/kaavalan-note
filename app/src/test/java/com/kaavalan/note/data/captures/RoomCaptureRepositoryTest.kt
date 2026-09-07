package com.kaavalan.note.data.captures

import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.CaptureDao
import com.kaavalan.note.data.local.SyncQueueDao
import com.kaavalan.note.data.local.entities.SyncQueueEntity
import com.kaavalan.note.data.local.entities.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.4.2 (F-09 / F-20) tests for [RoomCaptureRepository].
 *
 * The brief: when the user creates a capture, the note must be in
 * local Room *before* any network call so a Supabase outage can't
 * cost data. This test class exercises that offline-first
 * guarantee using an in-memory Room database (no real Supabase, no
 * real network).
 *
 * **What we assert.**
 *  1. `create()` writes a Room row with `syncStatus = PENDING_INSERT`
 *     and `processed = false`.
 *  2. `create()` enqueues a `sync_queue` row (`table = "captures"`,
 *     op = `INSERT`) so the existing [com.kaavalan.note.data.local.SyncEngine]
 *     and the new [com.kaavalan.note.data.sync.CaptureSyncWorker] can
 *     drain it.
 *  3. `create()` returns a [Capture] to the caller synchronously,
 *     with the client-generated UUID — the caller's hot path
 *     never sees a network error.
 *  4. `markProcessed()` updates the Room row to
 *     `processed = true, syncStatus = PENDING_UPDATE` and enqueues
 *     a `sync_queue` UPDATE row.
 *  5. The local row is durable: a `create()` followed by reading
 *     the row back returns the same id, even though we never
 *     touched the network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomCaptureRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var captureDao: CaptureDao
    private lateinit var syncQueueDao: SyncQueueDao
    private lateinit var repo: RoomCaptureRepository

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // v1.4.4: RoomCaptureRepository.create() and markProcessed()
        // now call WorkManagerInitializer.enqueueCaptureSync(context)
        // after enqueuing the sync_queue row. Kaavalan note disables
        // WorkManager's auto-init ContentProvider in the manifest
        // (see tools:node="remove" on
        // androidx.work.WorkManagerInitializer in AndroidManifest.xml)
        // so a bare WorkManager.getInstance(context) call would throw
        // IllegalStateException("WorkManager is not initialized
        // properly"). Initialize a test WorkManager here so the
        // per-write enqueue is a no-op (the work is enqueued into the
        // test driver; we don't assert it actually ran, just that
        // the call doesn't throw). Mirrors the pattern in
        // WorkManagerInitializerCaptureSyncTest.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .build(),
        )
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        captureDao = db.captureDao()
        syncQueueDao = db.syncQueueDao()
        repo = RoomCaptureRepository(
            dao = captureDao,
            syncQueueDao = syncQueueDao,
            // v1.4.4: pass the test context so the per-write
            // enqueueCaptureSync call has something to call
            // WorkManager.getInstance on. The WorkManager test
            // driver initialised above makes the call a no-op
            // (the work is enqueued into the driver, not the
            // real scheduler) and asserts the call didn't throw.
            context = context,
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    // ----- F-09 / F-20: offline-first create() -----

    @Test
    fun `create writes Room row with PENDING_INSERT and enqueues sync queue row`() = runTest {
        val capture = repo.create(rawText = "First note of the day", mode = CaptureMode.TEXT)

        // 1. The returned Capture has the right shape.
        assertEquals("First note of the day", capture.rawText)
        assertEquals(CaptureMode.TEXT, capture.mode)
        assertEquals(false, capture.processed)
        // 2. The Room row exists.
        val row = captureDao.getById(capture.id)
        assertNotNull("Room row must exist immediately after create()", row)
        assertEquals("First note of the day", row!!.rawText)
        // 3. F-09: the row is PENDING_INSERT (dirty / needs sync).
        // This is the wire the rest of the system uses to know the
        // row hasn't been pushed to Supabase yet.
        assertEquals(SyncStatus.PENDING_INSERT, row.syncStatus)
        // 4. The sync_queue row is enqueued so the worker / engine
        // can drain it.
        val queue = syncQueueDao.snapshot()
        assertEquals(1, queue.size)
        val entry = queue[0]
        assertEquals("captures", entry.table)
        assertEquals(capture.id, entry.rowId)
        assertEquals(SyncQueueEntity.OP_INSERT, entry.op)
        // 5. v2.2.1: the payload is empty, and in particular
        // carries no copy of the note.
        //
        // It used to carry the (id, rawText, mode) triple for a
        // drain to POST. v2.0.0 deleted Supabase and every drain
        // with it, so nothing read the payload and nothing deleted
        // the row -- it just kept a second copy of the note's text
        // in a table `RetentionWorker` does not touch. Its
        // `DELETE FROM captures` cleared the capture and left the
        // copy behind, so text the app had reported as deleted
        // stayed in the database indefinitely.
        //
        // This assertion used to require the opposite. It was
        // pinning the duplication in place.
        assertEquals(
            "the outbox payload must be empty; nothing reads it, and anything in it " +
                "outlives the capture the retention sweep deletes",
            "{}",
            entry.payloadJson,
        )
        assertFalse(
            "the note's text must not be copied into the outbox, was: ${entry.payloadJson}",
            entry.payloadJson.contains("First note of the day"),
        )
    }

    @Test
    fun `create returns synchronously without touching the network`() = runTest {
        // F-09 / F-20: a Supabase outage must not lose the user's
        // note. We don't pass a remote repository to the Room repo
        // (it doesn't take one); the test only asserts that the
        // local row exists after the call returns. A real Supabase
        // outage would manifest as a throw from the worker's
        // `insertCapture`; the user's note is already safe in
        // Room at that point.
        val capture = repo.create(rawText = "Offline note", mode = CaptureMode.TEXT)
        // Caller sees the local row immediately.
        assertEquals("Offline note", capture.rawText)
        // The Room row is durable across the call boundary.
        val row = captureDao.getById(capture.id)
        assertNotNull(row)
        assertEquals(SyncStatus.PENDING_INSERT, row!!.syncStatus)
    }

    @Test
    fun `create with PHOTO mode preserves mode through to Room row`() = runTest {
        val capture = repo.create(rawText = "OCR'd text from a photo", mode = CaptureMode.PHOTO)
        val row = captureDao.getById(capture.id)
        assertNotNull(row)
        assertEquals("PHOTO", row!!.mode)
        // v2.2.1: the mode lives on the Room row, asserted
        // above. The outbox row no longer duplicates it -- nor
        // the OCR'd text, which for a PHOTO capture is the whole
        // content of the photo.
        val entry = syncQueueDao.snapshot().first()
        assertEquals("{}", entry.payloadJson)
        assertFalse(
            "OCR text must not be copied into the outbox, was: ${entry.payloadJson}",
            entry.payloadJson.contains("OCR'd text from a photo"),
        )
    }

    // ----- F-09 / F-20: markProcessed() -----

    @Test
    fun `markProcessed updates Room row and enqueues UPDATE sync queue row`() = runTest {
        val capture = repo.create(rawText = "Some note", mode = CaptureMode.TEXT)
        // Drain the INSERT row from the queue so we can isolate the
        // UPDATE row that markProcessed enqueues.
        syncQueueDao.snapshot().forEach { syncQueueDao.deleteById(it.id) }

        repo.markProcessed(capture.id)

        // 1. Local row is processed = true.
        val row = captureDao.getById(capture.id)
        assertNotNull(row)
        assertEquals(true, row!!.processed)
        // 2. syncStatus is PENDING_UPDATE (dirty, awaiting wire PATCH).
        assertEquals(SyncStatus.PENDING_UPDATE, row.syncStatus)
        // 3. A UPDATE sync_queue row is enqueued.
        val queue = syncQueueDao.snapshot()
        assertEquals(1, queue.size)
        assertEquals(SyncQueueEntity.OP_UPDATE, queue[0].op)
        assertEquals(capture.id, queue[0].rowId)
    }

    @Test
    fun `markProcessed on a non-existent id is a no-op (no crash, no queue row)`() = runTest {
        repo.markProcessed("does-not-exist")
        val queue = syncQueueDao.snapshot()
        // No row in Room to read back, so no UPDATE enqueued.
        assertEquals(0, queue.size)
    }
}
