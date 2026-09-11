package com.kaavalan.note.ui.subdivision

import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.data.subdivision.Matter
import com.kaavalan.note.data.subdivision.Station
import com.kaavalan.note.data.subdivision.SubdivisionProfile
import com.kaavalan.note.data.subdivision.SubdivisionProjections
import com.kaavalan.note.data.subdivision.SubdivisionReview
import com.kaavalan.note.data.subdivision.StaffPosting

/**
 * The whole subdivision read model in one immutable value, plus the pure row projections
 * the three screens render. Nothing here reads the clock, Room or Android — `now` is
 * always a parameter — so every count on screen is reproducible in a plain unit test.
 */
data class SubdivisionUiState(
    val loading: Boolean = true,
    /** False in the private workspace: the CRM is a normal-workspace feature in this version. */
    val available: Boolean = true,
    val profile: SubdivisionProfile? = null,
    val stations: List<Station> = emptyList(),
    val matters: List<Matter> = emptyList(),
    val postings: List<StaffPosting> = emptyList(),
    val reviews: List<SubdivisionReview> = emptyList(),
    val contacts: List<Person> = emptyList(),
    /** Normal-workspace, non-sensitive work only. Every count and list below derives from this. */
    val instructions: List<Instruction> = emptyList(),
    val error: String? = null,
) {
    val isConfigured: Boolean get() = profile != null
    val ready: Boolean get() = !loading && error == null && available

    fun station(id: String?): Station? = id?.let { key -> stations.firstOrNull { it.id == key } }
    fun matter(id: String?): Matter? = id?.let { key -> matters.firstOrNull { it.id == key } }
    fun contact(id: String?): Person? = id?.let { key -> contacts.firstOrNull { it.id == key } }
}

/** One row of the Stations segment. */
data class StationRow(
    val station: Station,
    val activeStaff: Int,
    val openInstructions: Int,
    val activeMatters: Int,
) {
    val id: String get() = station.id
}

/** One row of the Staff segment. An inactive posting stays listed and stays searchable. */
data class StaffRow(
    val person: Person,
    val stationName: String?,
    val openInstructions: Int,
)

/** One row of the Matters screen. */
data class MatterRow(
    val matter: Matter,
    val stationName: String?,
    val openInstructions: Int,
) {
    val id: String get() = matter.id
}

/**
 * The recorded work context of one instruction, for the detail sheet's context row.
 *
 * [stationName] is the station recorded ON THE INSTRUCTION, which is not necessarily the
 * responsible contact's current posting — that is the whole point of recording it.
 */
data class WorkContext(val stationName: String?, val matterTitle: String?, val reference: String?) {
    val summary: String
        get() = listOf(
            stationName ?: "No station recorded",
            matterTitle ?: "No matter",
        ).joinToString(" · ")
}


private val Instruction.isOpenWork: Boolean
    get() = with(SubdivisionProjections) { isOpenWork }

fun stationRows(state: SubdivisionUiState, includeArchived: Boolean, query: String): List<StationRow> =
    state.stations
        .filter { includeArchived == it.archived }
        .filter { matches(query, it.name, it.kind, it.notes) }
        .map { station ->
            StationRow(
                station = station,
                activeStaff = state.contacts.count { it.stationId == station.id && it.isStaff && it.staffActive },
                openInstructions = state.instructions.count { it.stationId == station.id && it.isOpenWork },
                activeMatters = state.matters.count { it.stationId == station.id && !it.archived },
            )
        }

fun staffRows(state: SubdivisionUiState, query: String): List<StaffRow> =
    state.contacts
        .filter { it.isStaff }
        .filter { matches(query, it.name, it.designation, it.station, it.responsibilities) }
        .map { person ->
            StaffRow(
                person = person,
                stationName = state.station(person.stationId)?.name,
                openInstructions = state.instructions.count {
                    it.personId == person.id && it.isOpenWork
                },
            )
        }
        .sortedWith(compareByDescending<StaffRow> { it.person.staffActive }.thenBy { it.person.name.lowercase() })

fun matterRows(state: SubdivisionUiState, includeArchived: Boolean, query: String): List<MatterRow> =
    state.matters
        .filter { includeArchived == it.archived }
        .filter { matches(query, it.title, it.reference, it.description) }
        .map { matter ->
            MatterRow(
                matter = matter,
                stationName = state.station(matter.stationId)?.name,
                openInstructions = state.instructions.count { it.matterId == matter.id && it.isOpenWork },
            )
        }

fun workContextOf(state: SubdivisionUiState, instruction: Instruction): WorkContext {
    val matter = state.matter(instruction.matterId)
    return WorkContext(
        stationName = state.station(instruction.stationId)?.name,
        matterTitle = matter?.title,
        reference = matter?.reference?.takeIf { it.isNotBlank() },
    )
}

/**
 * The station and matter text an instruction should be findable by. Fed into the
 * Instructions-tab search alongside the existing text, contact and journal terms.
 */
fun searchableContext(state: SubdivisionUiState): Map<String, String> {
    if (state.stations.isEmpty() && state.matters.isEmpty()) return emptyMap()
    return state.instructions.associate { item ->
        val matter = state.matter(item.matterId)
        item.id to listOfNotNull(
            state.station(item.stationId)?.name,
            matter?.title,
            matter?.reference?.takeIf { it.isNotBlank() },
        ).joinToString(" ")
    }.filterValues { it.isNotBlank() }
}

/** Staff postings for one contact, newest first. Never silently replaced by a later change. */
fun postingHistory(state: SubdivisionUiState, personId: String): List<StaffPosting> =
    state.postings.filter { it.personId == personId }.sortedByDescending { it.recordedAt }

private fun matches(query: String, vararg fields: String?): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    val haystack = fields.filterNotNull().joinToString(" ")
    return needle.split(Regex("\\s+")).all { haystack.contains(it, ignoreCase = true) }
}
