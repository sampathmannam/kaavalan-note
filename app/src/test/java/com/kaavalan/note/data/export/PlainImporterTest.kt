package com.kaavalan.note.data.export

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.PersonDao
import com.kaavalan.note.data.local.TagDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.0.1 (PM rating): the CSV/JSON importer. Round-trips a
 * snapshot the [PlainExporter] wrote back into the same
 * DB. Re-importing the same file is idempotent (upsert
 * by id).
 *
 * **Why Robolectric.** The [PlainImporter] needs an
 * [Application] context to open the input URI via
 * [android.content.ContentResolver]. Robolectric provides
 * a real Application with a real ContentResolver (the
 * SAF openInputStream is stubbed via the test URI scheme
 * below).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlainImporterTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var personDao: PersonDao
    private lateinit var instructionDao: InstructionDao
    private lateinit var tagDao: TagDao
    private lateinit var exporter: PlainExporter
    private lateinit var importer: PlainImporter

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(testDispatcher.asExecutor())
            .setTransactionExecutor(testDispatcher.asExecutor())
            .build()
        personDao = db.personDao()
        instructionDao = db.instructionDao()
        tagDao = db.tagDao()
        exporter = PlainExporter(personDao, instructionDao, tagDao, db.instructionTagDao())
        importer = PlainImporter(context, personDao, instructionDao, tagDao)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `csv round-trip exports and re-imports the same rows`() = runTest {
        // Seed the DB
        personDao.upsert(
            com.kaavalan.note.data.local.entities.PersonEntity(
                id = "p1", name = "Ramu", designation = "SHO", station = "Bandipora",
                phone = null, userId = "",
                createdAt = "2026-08-24T00:00:00Z", updatedAt = "2026-08-24T00:00:00Z",
                syncStatus = com.kaavalan.note.data.local.entities.SyncStatus.SYNCED,
            ),
        )
        instructionDao.upsert(
            com.kaavalan.note.data.local.entities.InstructionEntity(
                id = "i1", personId = "p1", direction = "INCOMING", status = "OPEN",
                source = "TEXT", priority = "NORMAL", title = "Buy milk",
                rawText = "Buy milk on the way home", dueAt = null,
                capturedAt = "2026-08-24T00:00:00Z",
                createdAt = "2026-08-24T00:00:00Z", updatedAt = "2026-08-24T00:00:00Z",
            ),
        )
        tagDao.upsert(
            com.kaavalan.note.data.local.entities.TagEntity(
                id = "t1", name = "urgent", kind = "FREE", color = null,
                userId = "", usageCount = 1, lastUsedAt = null,
                createdAt = "2026-08-24T00:00:00Z", updatedAt = "2026-08-24T00:00:00Z",
                syncStatus = com.kaavalan.note.data.local.entities.SyncStatus.SYNCED,
            ),
        )
        advanceUntilIdle()

        // Export → CSV
        val snap = exporter.snapshot()
        val csv = exporter.toCsv(snap)

        // Wipe the DB
        db.clearAllTables()

        // Re-import via a fake URI (in-memory bytes via a
        // custom file:// path that Robolectric's
        // ContentResolver can read).
        val uri = writeTestFile("roundtrip.csv", csv)
        val report = importer.importFromUri(uri).getOrThrow()
        advanceUntilIdle()

        assertEquals(1, report.peopleInserted)
        assertEquals(1, report.instructionsInserted)
        assertEquals(1, report.tagsInserted)
        assertEquals(0, report.peopleUpdated)

        val reloaded = exporter.snapshot()
        assertEquals(1, reloaded.people.size)
        assertEquals("Ramu", reloaded.people[0].name)
        assertEquals(1, reloaded.instructions.size)
        assertEquals("Buy milk", reloaded.instructions[0].title)
        assertEquals(1, reloaded.tags.size)
        assertEquals("urgent", reloaded.tags[0].name)
    }

    @Test
    fun `re-importing the same file is idempotent (no new inserts)`() = runTest {
        personDao.upsert(
            com.kaavalan.note.data.local.entities.PersonEntity(
                id = "p1", name = "Ramu", designation = null, station = null,
                phone = null, userId = "",
                createdAt = "2026-08-24T00:00:00Z", updatedAt = "2026-08-24T00:00:00Z",
                syncStatus = com.kaavalan.note.data.local.entities.SyncStatus.SYNCED,
            ),
        )
        advanceUntilIdle()

        val snap = exporter.snapshot()
        val csv = exporter.toCsv(snap)
        val uri = writeTestFile("reimport.csv", csv)

        // First import: row already exists, so it counts
        // as updated.
        val first = importer.importFromUri(uri).getOrThrow()
        advanceUntilIdle()
        assertEquals(0, first.peopleInserted)
        assertEquals(1, first.peopleUpdated)
    }

    @Test
    fun `json round-trip preserves the same fields as csv`() = runTest {
        personDao.upsert(
            com.kaavalan.note.data.local.entities.PersonEntity(
                id = "p1", name = "Priya", designation = "DSP", station = "Srinagar",
                phone = null, userId = "",
                createdAt = "2026-08-24T00:00:00Z", updatedAt = "2026-08-24T00:00:00Z",
                syncStatus = com.kaavalan.note.data.local.entities.SyncStatus.SYNCED,
            ),
        )
        advanceUntilIdle()

        val snap = exporter.snapshot()
        val json = exporter.toJson(snap)
        val uri = writeTestFile("roundtrip.json", json)
        db.clearAllTables()

        val report = importer.importFromUri(uri).getOrThrow()
        advanceUntilIdle()
        assertEquals(1, report.peopleInserted)

        val reloaded = exporter.snapshot().people
        assertEquals(1, reloaded.size)
        assertEquals("Priya", reloaded[0].name)
        assertEquals("DSP", reloaded[0].designation)
        assertEquals("Srinagar", reloaded[0].station)
    }

    /**
     * Write a file into the app's cache dir and return
     * a `file://` URI. Robolectric's ContentResolver
     * can read these via FileProvider-equivalent paths.
     */
    @Test fun `journal and independent dates survive both portable formats`() = runTest {
        val journal = com.kaavalan.note.data.instructions.InstructionJournal.encode(listOf(
            com.kaavalan.note.data.instructions.InstructionUpdate("update", "2026-09-10T01:00:00Z", "Called, confirmed\nதமிழ்", "IN_PROGRESS", 1800000000000L)))
        val record = com.kaavalan.note.data.local.entities.InstructionEntity("note", null, "SELF", "IN_PROGRESS", "TEXT", "NORMAL",
            "Review", "Review patrol deployment", null, "2026-09-10T01:00:00Z", "2026-09-10T01:00:00Z", "2026-09-10T01:00:00Z",
            deadlineAtMs = 1810000000000L, dueAtMs = 1800000000000L, updatesJson = journal)
        listOf("json", "csv").forEach { format ->
            instructionDao.upsert(record)
            val snapshot = exporter.snapshot()
            val text = if (format == "json") exporter.toJson(snapshot) else exporter.toCsv(snapshot)
            val uri = writeTestFile("journal.$format", text)
            db.clearAllTables()
            importer.importFromUri(uri).getOrThrow()
            val restored = instructionDao.getById("note")!!
            assertEquals(record.deadlineAtMs, restored.deadlineAtMs)
            assertEquals(record.dueAtMs, restored.dueAtMs)
            assertEquals(journal, restored.updatesJson)
        }
    }

    private fun writeTestFile(name: String, contents: String): Uri {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dir = java.io.File(context.cacheDir, "importer-test").apply { mkdirs() }
        val file = java.io.File(dir, name)
        file.writeText(contents)
        return Uri.fromFile(file)
    }
}
