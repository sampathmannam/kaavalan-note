package com.kaavalan.note.data.vault

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.InstructionTagCrossRef
import com.kaavalan.note.data.local.entities.PersonEntity
import com.kaavalan.note.data.local.entities.SyncStatus
import com.kaavalan.note.data.local.entities.TagEntity
import com.kaavalan.note.data.tags.TagKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Tier 1.1 (v2.0): the on-device end-to-end round-trip
 * for the .kaavalan-note-vault backup. This test runs on a real
 * device or emulator (androidTest) so the Argon2id native
 * library (`libargon2jni.so`) is on the classpath.
 *
 * **Strategy.**
 *  1. Build a real Room DB on disk (file-based, not
 *     in-memory — the exporter reads the on-disk file).
 *  2. Seed 3 people + 2 instructions + 1 tag + 1
 *     instruction_tag cross-ref.
 *  3. Run [VaultExporter.export] to a temp file with
 *     passphrase "verylongstrongpassphrase".
 *  4. Wipe the DB.
 *  5. Run [VaultImporter.import] from the temp file.
 *  6. Re-open the DB and assert the 3 people + 2
 *     instructions + 1 tag + 1 cross-ref are back.
 *
 * **Negative path.** Also covered: a wrong passphrase
 * must surface [VaultError.IncorrectPassphrase] (the
 * `AEADBadTagException` -> `IncorrectPassphrase` mapping
 * in the importer).
 *
 * To run:
 * ```
 * .\gradlew.bat :app:connectedDebugAndroidTest --tests "com.kaavalan.note.data.vault.VaultEndToEndTest"
 * ```
 */
@RunWith(AndroidJUnit4::class)
class VaultEndToEndTest {

    private val passphrase = "verylongstrongpassphrase".toCharArray()
    private val wrongPassphrase = "wrongpassphrase!!!".toCharArray()
    private lateinit var db: AppDatabase
    private lateinit var tempFile: File

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Use a real (file-backed) Room DB. The exporter
        // reads `context.getDatabasePath(NAME)`, which an
        // in-memory DB does not back.
        db = androidx.room.Room.databaseBuilder(ctx, AppDatabase::class.java, AppDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        tempFile = File.createTempFile("vault-e2e-", ".kaavalan-note-vault", ctx.cacheDir)
        tempFile.delete()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::tempFile.isInitialized && tempFile.exists()) tempFile.delete()
    }

    @Test
    fun exportThenImport_roundTripsAllTables() = runBlocking {
        val now = java.time.Instant.now().toString()
        val people = listOf(
            PersonEntity(
                id = "p1", name = "Inspector Ramesh", designation = "SHO",
                station = "Thanjavur Town", phone = null, userId = "u",
                createdAt = now, updatedAt = now, isSensitive = false,
                syncStatus = SyncStatus.SYNCED,
            ),
            PersonEntity(
                id = "p2", name = "DSP Kavitha", designation = "DSP",
                station = "Thanjavur Rural", phone = null, userId = "u",
                createdAt = now, updatedAt = now, isSensitive = false,
                syncStatus = SyncStatus.SYNCED,
            ),
            PersonEntity(
                id = "p3", name = "SP Selvam", designation = "SP",
                station = "Thanjavur District", phone = null, userId = "u",
                createdAt = now, updatedAt = now, isSensitive = false,
                syncStatus = SyncStatus.SYNCED,
            ),
        )
        people.forEach { db.personDao().upsert(it) }
        val tag = TagEntity(
            id = "t1", name = "priority", kind = TagKind.PRIORITY.name,
            color = null, usageCount = 0, lastUsedAt = null, userId = "u",
            createdAt = now, updatedAt = now, syncStatus = SyncStatus.SYNCED,
        )
        db.tagDao().upsert(tag)
        val instructions = listOf(
            InstructionEntity(
                id = UUID.randomUUID().toString(), personId = "p1",
                direction = "INCOMING", status = "OPEN", source = "TEXT",
                priority = "HIGH",
                title = "Temple land inquiry", rawText = "follow up by Friday",
                dueAt = null, capturedAt = now, createdAt = now, updatedAt = now,
                isSensitive = false, syncStatus = SyncStatus.SYNCED,
                completedAt = null, droppedReason = null, nextActionAt = null,
            ),
            InstructionEntity(
                id = UUID.randomUUID().toString(), personId = "p2",
                direction = "OUTGOING", status = "OPEN", source = "TEXT",
                priority = "NORMAL",
                title = "Bandobast plan", rawText = "draft a plan and send across",
                dueAt = null, capturedAt = now, createdAt = now, updatedAt = now,
                isSensitive = false, syncStatus = SyncStatus.SYNCED,
                completedAt = null, droppedReason = null, nextActionAt = null,
            ),
        )
        instructions.forEach { db.instructionDao().upsert(it) }
        db.instructionTagDao().attach(
            InstructionTagCrossRef(instructionId = instructions[0].id, tagId = tag.id),
        )

        // Export.
        val crypto = VaultCrypto()
        val exporter = VaultExporter(
            ApplicationProvider.getApplicationContext(), crypto, db,
        )
        val exportResult = exporter.export(Uri.fromFile(tempFile), passphrase.copyOf())
        assertTrue("export must succeed: $exportResult", exportResult.isSuccess)
        assertTrue("export file must be > 56 bytes (header)", tempFile.length() > 56)
        val fileBytesBefore = tempFile.readBytes()
        // The file must start with the BATO magic.
        assertArrayEquals("BATO".toByteArray(Charsets.US_ASCII), fileBytesBefore.copyOfRange(0, 4))
        assertEquals(1.toByte(), fileBytesBefore[4]) // version

        // Wipe the DB so the import is the only source of
        // truth for the assertions.
        db.clearAllTables()
        // close + reopen so the import sees an empty DB
        // file (the importer overwrites the on-disk file
        // after closing the Room instance).
        db.close()
        db = androidx.room.Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java, AppDatabase.NAME,
        ).allowMainThreadQueries().build()
        assertEquals(0, db.personDao().snapshot().size)

        // Import.
        val importer = VaultImporter(
            ApplicationProvider.getApplicationContext(), crypto, db,
        )
        val importResult = importer.import(
            Uri.fromFile(tempFile), passphrase.copyOf(),
        )
        assertTrue("import must succeed: $importResult", importResult.isSuccess)

        // The DB must be re-opened after the importer
        // closed it; the Hilt singleton would re-open on
        // the next DAO call, but in a test we need to
        // manually re-open.
        db.close()
        db = androidx.room.Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java, AppDatabase.NAME,
        ).allowMainThreadQueries().build()
        val restoredPeople = db.personDao().snapshot()
        val restoredInstructions = db.instructionDao().snapshot()
        val restoredTags = db.tagDao().observeAll()
        val firstTag = restoredTags.first()
        assertEquals(3, restoredPeople.size)
        assertEquals(2, restoredInstructions.size)
        assertEquals(1, firstTag.size)
        assertTrue("Ramesh must be in the restored people", restoredPeople.any { it.name == "Inspector Ramesh" })
        assertTrue("Temple land inquiry must be in the restored instructions", restoredInstructions.any { it.title == "Temple land inquiry" })
    }

    @Test
    fun wrongPassphrase_failsWithIncorrectPassphrase() = runBlocking {
        // Seed a single row so the export is non-trivial.
        val now = java.time.Instant.now().toString()
        db.personDao().upsert(
            PersonEntity(
                id = "p1", name = "Test", designation = null, station = null, phone = null,
                userId = "u", createdAt = now, updatedAt = now, isSensitive = false,
                syncStatus = SyncStatus.SYNCED,
            ),
        )
        val crypto = VaultCrypto()
        val exporter = VaultExporter(
            ApplicationProvider.getApplicationContext(), crypto, db,
        )
        val exportResult = exporter.export(
            Uri.fromFile(tempFile), passphrase.copyOf(),
        )
        assertTrue(exportResult.isSuccess)

        // Import with a wrong passphrase. The exporter
        // closed the Room instance; reopen for the test.
        db.close()
        db = androidx.room.Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java, AppDatabase.NAME,
        ).allowMainThreadQueries().build()

        val importer = VaultImporter(
            ApplicationProvider.getApplicationContext(), crypto, db,
        )
        val result = importer.import(
            Uri.fromFile(tempFile), wrongPassphrase.copyOf(),
        )
        assertTrue("import must fail with wrong passphrase", result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(
            "expected IncorrectPassphrase, got ${err?.javaClass?.name}: $err",
            err is VaultError.IncorrectPassphrase,
        )
    }
}
