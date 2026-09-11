package com.kaavalan.note.data.export

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.PersonEntity
import com.kaavalan.note.data.local.entities.SyncStatus
import com.kaavalan.note.data.local.entities.TagEntity
import com.kaavalan.note.data.subdivision.Matter
import com.kaavalan.note.data.subdivision.Station
import com.kaavalan.note.data.subdivision.SubdivisionProfile
import com.kaavalan.note.data.subdivision.SubdivisionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tier 1.7 (v2.0): the plain export.
 *
 * v2.6.0 moved this off mocked DAOs and onto a real in-memory Room database. The exporter
 * now reads its whole snapshot inside one transaction, and a mocked database cannot
 * demonstrate that a transaction happened at all - the handover is explicit that mocks
 * alone cannot prove a transaction guarantee.
 *
 * Robolectric provides `org.json`, which the JSON export uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlainExporterTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var exporter: PlainExporter

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(testDispatcher.asExecutor())
            .setTransactionExecutor(testDispatcher.asExecutor())
            .build()
        exporter = PlainExporter(db.personDao(), db.instructionDao(), db.tagDao(), db.instructionTagDao(), db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun person(id: String, name: String, station: String? = null) = PersonEntity(
        id = id, name = name, designation = "SHO", station = station, phone = null,
        userId = "u", createdAt = "2026-08-12T00:00:00Z",
        updatedAt = "2026-08-12T00:00:00Z",
        isSensitive = false, syncStatus = SyncStatus.SYNCED,
    )

    private fun instruction(
        id: String,
        personId: String?,
        title: String,
        rawText: String,
    ) = InstructionEntity(
        id = id, personId = personId, direction = "OUTGOING", status = "OPEN",
        source = "TEXT", priority = "NORMAL", title = title, rawText = rawText,
        dueAt = null, capturedAt = "2026-08-12T00:00:00Z",
        createdAt = "2026-08-12T00:00:00Z",
        updatedAt = "2026-08-12T00:00:00Z",
        isSensitive = false, syncStatus = SyncStatus.SYNCED,
        completedAt = null, droppedReason = null, nextActionAt = 1730000000000L,
    )

    private fun tag(id: String, name: String, kind: String) = TagEntity(
        id = id, name = name, kind = kind, color = null, usageCount = 0,
        lastUsedAt = null, userId = "u", createdAt = "2026-08-12T00:00:00Z",
        updatedAt = "2026-08-12T00:00:00Z", syncStatus = SyncStatus.SYNCED,
    )

    private suspend fun seed() {
        db.personDao().upsertAll(
            listOf(person("p1", "Inspector Ramesh", "Thanjavur Town"), person("p2", "DSP Kavitha")),
        )
        db.instructionDao().upsertAll(
            listOf(
                instruction("i1", "p1", "Temple land inquiry", "follow up by Friday"),
                instruction("i2", "p2", "Bandobast plan", "draft a plan and send across"),
            ),
        )
        db.tagDao().upsertAll(listOf(tag("t1", "priority", "PRIORITY"), tag("t2", "follow-up", "FREE")))
    }

    @Test
    fun `toCsv includes UTF-8 BOM and all sections`() = runTest {
        seed()
        val csv = exporter.toCsv(exporter.snapshot())
        assertTrue("CSV must start with UTF-8 BOM", csv.startsWith("﻿"))
        assertTrue("CSV must include people header", csv.contains("id,name,designation"))
        assertTrue("CSV must include person row", csv.contains("Inspector Ramesh"))
        assertTrue("CSV must include instructions header", csv.contains("next_action_at"))
        assertTrue("CSV must include instruction row", csv.contains("Temple land inquiry"))
        assertTrue("CSV must include tags header", csv.contains("usage_count"))
        assertTrue("CSV must include tag row", csv.contains("priority"))
    }

    @Test
    fun `toCsv keeps the historic column order and appends the new columns`() = runTest {
        val csv = exporter.toCsv(exporter.snapshot())
        val peopleHeader = csv.lineSequence().first { it.contains("id,name,designation") }.removePrefix("﻿")
        assertTrue(
            "the pre-2.6.0 person columns must stay first, in order; got $peopleHeader",
            peopleHeader.startsWith("id,name,designation,station,phone,is_sensitive,created_at,updated_at"),
        )
        val instructionHeader = csv.lineSequence().first { it.startsWith("id,person_id,direction") }
        assertTrue(
            "the pre-2.6.0 instruction columns must stay first, in order; got $instructionHeader",
            instructionHeader.startsWith(
                "id,person_id,direction,status,source,priority,title,raw_text,due_at,captured_at," +
                    "created_at,updated_at,next_action_at,deadline_at_ms,updates_json,due_at_ms",
            ),
        )
        assertTrue("the work context must be appended", instructionHeader.contains(",station_id,matter_id,"))
    }

    @Test
    fun `toCsv correctly escapes commas in values`() = runTest {
        db.personDao().upsert(person("p1", "Ramesh, Kumar", "Town, North"))
        val csv = exporter.toCsv(exporter.snapshot())
        assertTrue("comma in name must be quoted", csv.contains("\"Ramesh, Kumar\""))
        assertTrue("comma in station must be quoted", csv.contains("\"Town, North\""))
    }

    @Test
    fun `toJson round-trips people, instructions, and tags`() = runTest {
        seed()
        val json = exporter.toJson(exporter.snapshot())
        assertTrue("json must contain 'people'", json.contains("\"people\""))
        assertTrue("json must contain 'instructions'", json.contains("\"instructions\""))
        assertTrue("json must contain 'tags'", json.contains("\"tags\""))
        assertTrue("json must contain 'subdivision'", json.contains("\"subdivision\""))
        assertTrue("person row must be present", json.contains("Inspector Ramesh"))
        assertTrue("instruction row must be present", json.contains("Temple land inquiry"))
        assertTrue("tag row must be present", json.contains("priority"))
    }

    @Test
    fun `toCsv empty DB returns BOM and headers only`() = runTest {
        val csv = exporter.toCsv(exporter.snapshot())
        assertTrue(csv.startsWith("﻿"))
        assertTrue(csv.contains("id,name,designation"))
        assertTrue(csv.contains("next_action_at"))
        assertTrue(csv.contains("usage_count"))
        assertTrue("the subdivision block is labelled even when empty", csv.contains("subdivision_json"))
    }

    @Test
    fun `csv and json both carry the subdivision profile, stations and matters`() = runTest {
        val now = "2026-09-10T09:00:00Z"
        db.subdivisionDao().saveProfile(SubdivisionProfile("visible", "Ambasamudram", "Tirunelveli", "K. S.", now))
        val station = Station(
            id = "st-1",
            vaultMode = "visible",
            name = "Kalakad",
            nameKey = SubdivisionRepository.stationKey("Kalakad"),
            createdAt = now,
            updatedAt = now,
        )
        db.subdivisionDao().saveStation(station)
        db.subdivisionDao().saveMatter(
            Matter(
                id = "m-1",
                vaultMode = "visible",
                title = "Sand mining inquiry",
                stationId = station.id,
                reference = "REF/2026/14",
                createdAt = now,
                updatedAt = now,
            ),
        )

        val snapshot = exporter.snapshot()
        assertEquals(1, snapshot.subdivision.profiles.size)
        assertEquals(1, snapshot.subdivision.stations.size)
        assertEquals(1, snapshot.subdivision.matters.size)

        val csv = exporter.toCsv(snapshot)
        assertTrue("the CSV subdivision block must be labelled", csv.contains("subdivision_json"))
        assertTrue("the CSV must carry the subdivision name", csv.contains("Ambasamudram"))
        assertTrue("the CSV must carry the matter", csv.contains("Sand mining inquiry"))

        // Parsed, not substring-matched: org.json escapes a forward slash as \/, so a
        // naive contains() check would fail on a reference the export got right.
        val json = org.json.JSONObject(exporter.toJson(snapshot)).getJSONObject("subdivision")
        assertEquals(
            "Ambasamudram",
            json.getJSONArray("profiles").getJSONObject(0).getString("name"),
        )
        assertEquals("Kalakad", json.getJSONArray("stations").getJSONObject(0).getString("name"))
        assertEquals(
            "REF/2026/14",
            json.getJSONArray("matters").getJSONObject(0).getString("reference"),
        )

    }
}
