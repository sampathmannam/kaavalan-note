package com.kaavalan.note.ui.subdivision

import com.kaavalan.note.data.instructions.Direction
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.Priority
import com.kaavalan.note.data.instructions.Source
import com.kaavalan.note.data.instructions.Status
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.data.subdivision.Matter
import com.kaavalan.note.data.subdivision.StaffPosting
import com.kaavalan.note.data.subdivision.Station
import com.kaavalan.note.data.subdivision.SubdivisionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The row projections behind the three CRM lists, as pure functions over one state value. */
class SubdivisionModelTest {

    private val now = "2026-09-10T09:00:00Z"

    private fun station(id: String, name: String, archived: Boolean = false, kind: String = "Station") = Station(
        id = id, vaultMode = "visible", name = name, nameKey = SubdivisionRepository.stationKey(name),
        kind = kind, notes = "", archived = archived, createdAt = now, updatedAt = now,
    )

    private fun matter(
        id: String,
        title: String,
        stationId: String? = null,
        archived: Boolean = false,
        reference: String = "",
    ) = Matter(
        id = id, vaultMode = "visible", title = title, stationId = stationId, reference = reference,
        description = "", archived = archived, createdAt = now, updatedAt = now,
    )

    private fun person(
        id: String,
        name: String,
        stationId: String? = null,
        isStaff: Boolean = false,
        active: Boolean = true,
        designation: String? = "SI",
        responsibilities: String = "",
    ) = Person(
        id = id, name = name, designation = designation, station = null, phone = null,
        updatedAt = now, stationId = stationId, isStaff = isStaff,
        staffActive = active, responsibilities = responsibilities,
    )


    private fun work(
        id: String,
        personId: String? = null,
        stationId: String? = null,
        matterId: String? = null,
        status: Status = Status.OPEN,
    ) = Instruction(
        id = id, personId = personId, direction = Direction.OUTGOING, status = status, source = Source.TEXT,
        priority = Priority.NORMAL, title = "Title $id", rawText = "Body $id", dueAt = null,
        capturedAt = now, createdAt = now, updatedAt = now, stationId = stationId, matterId = matterId,
    )

    private val state = SubdivisionUiState(
        loading = false,
        stations = listOf(
            station("st-kalakad", "Kalakad"),
            station("st-nanguneri", "Nanguneri", kind = "Outpost"),
            station("st-old", "Old Wing", archived = true),
        ),
        matters = listOf(
            matter("m-sand", "Sand mining inquiry", "st-kalakad", reference = "REF/1"),
            matter("m-monsoon", "Monsoon drive"),
            matter("m-closed", "Closed drive", archived = true),
        ),
        postings = listOf(
            StaffPosting("po-1", "visible", "p-ramesh", "", "Kalakad", "Classified", "2026-09-01T09:00:00Z"),
            StaffPosting("po-2", "visible", "p-ramesh", "Kalakad", "Nanguneri", "Transferred", "2026-09-05T09:00:00Z"),
        ),
        contacts = listOf(
            person("p-ramesh", "Ramesh", "st-kalakad", isStaff = true, responsibilities = "Coastal beat"),
            person("p-kavitha", "Kavitha", "st-kalakad", isStaff = true, active = false),
            person("p-arun", "Arun", isStaff = true),
            person("p-ordinary", "Ordinary Contact"),
        ),
        instructions = listOf(
            work("i-1", "p-ramesh", "st-kalakad", "m-sand"),
            work("i-2", "p-ramesh", "st-kalakad", null, status = Status.DONE),
            work("i-3", "p-kavitha", "st-nanguneri", "m-monsoon"),
            work("i-4", null, null, null),
        ),
    )

    @Test
    fun `station rows count active staff, open work and active matters`() {
        val rows = stationRows(state, includeArchived = false, query = "")
        assertEquals(listOf("Kalakad", "Nanguneri"), rows.map { it.station.name })
        val kalakad = rows.single { it.station.name == "Kalakad" }
        assertEquals("only the active posting counts", 1, kalakad.activeStaff)
        assertEquals("the completed instruction is not open work", 1, kalakad.openInstructions)
        assertEquals(1, kalakad.activeMatters)
    }

    @Test
    fun `the archived filter is a separate list, not mixed into the active one`() {
        assertTrue(stationRows(state, includeArchived = false, "").none { it.station.archived })
        assertEquals(
            listOf("Old Wing"),
            stationRows(state, includeArchived = true, "").map { it.station.name },
        )
    }

    @Test
    fun `station search matches name and unit type`() {
        assertEquals(listOf("Nanguneri"), stationRows(state, false, "nangu").map { it.station.name })
        assertEquals(listOf("Nanguneri"), stationRows(state, false, "outpost").map { it.station.name })
        assertTrue(stationRows(state, false, "nowhere").isEmpty())
    }

    @Test
    fun `staff rows list active postings first and keep inactive ones searchable`() {
        val rows = staffRows(state, "")
        assertEquals(
            "active first, then inactive, alphabetical within each group",
            listOf("Arun", "Ramesh", "Kavitha"),
            rows.map { it.person.name },
        )
        assertTrue("an ordinary contact is not staff", rows.none { it.person.name == "Ordinary Contact" })
        assertEquals(
            listOf("Kavitha"),
            staffRows(state, "kavitha").map { it.person.name },
        )
        assertNull(
            "an officer with no posting reads as unassigned rather than blank",
            rows.single { it.person.name == "Arun" }.stationName,
        )
        assertEquals("Kalakad", rows.single { it.person.name == "Ramesh" }.stationName)
    }

    @Test
    fun `staff search matches responsibilities too`() {
        assertEquals(listOf("Ramesh"), staffRows(state, "coastal").map { it.person.name })
    }

    @Test
    fun `matter rows show the station or say subdivision-wide, and count open work`() {
        val rows = matterRows(state, includeArchived = false, query = "")
        assertEquals(listOf("Sand mining inquiry", "Monsoon drive"), rows.map { it.matter.title })
        assertEquals("Kalakad", rows.first().stationName)
        assertNull("a subdivision-wide matter has no station name", rows[1].stationName)
        assertEquals(1, rows.first().openInstructions)
    }

    @Test
    fun `matter search matches the reference`() {
        assertEquals(listOf("Sand mining inquiry"), matterRows(state, false, "REF/1").map { it.matter.title })
    }

    @Test
    fun `the work context of an instruction is the station recorded on it`() {
        val context = workContextOf(state, state.instructions.first { it.id == "i-1" })
        assertEquals("Kalakad", context.stationName)
        assertEquals("Sand mining inquiry", context.matterTitle)
        assertEquals("REF/1", context.reference)
        assertTrue(context.summary.contains("Kalakad") && context.summary.contains("Sand mining inquiry"))

        val unlinked = workContextOf(state, state.instructions.first { it.id == "i-4" })
        assertNull(unlinked.stationName)
        assertNull(unlinked.matterTitle)
        assertEquals("No station recorded · No matter", unlinked.summary)
    }

    @Test
    fun `searchable context makes station and matter text findable per instruction`() {
        val index = searchableContext(state)
        assertTrue(index.getValue("i-1").contains("Kalakad"))
        assertTrue(index.getValue("i-1").contains("Sand mining inquiry"))
        assertTrue(index.getValue("i-1").contains("REF/1"))
        assertTrue(
            "an instruction with no context contributes nothing to the index",
            !index.containsKey("i-4"),
        )
        assertTrue(
            "an empty record produces an empty index rather than a map of blanks",
            searchableContext(SubdivisionUiState(loading = false)).isEmpty(),
        )
    }

    @Test
    fun `posting history is newest first and is never collapsed`() {
        val history = postingHistory(state, "p-ramesh")
        assertEquals(listOf("po-2", "po-1"), history.map { it.id })
        assertTrue(postingHistory(state, "p-kavitha").isEmpty())
    }

    @Test
    fun `state lookups return null rather than throwing on an unknown id`() {
        assertNull(state.station("nope"))
        assertNull(state.matter("nope"))
        assertNull(state.contact("nope"))
        assertNull(state.station(null))
    }
}
