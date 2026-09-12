package com.kaavalan.note.ui.subdivision

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kaavalan.note.ui.workspace.WorkCard
import java.time.LocalDate

/** The two local categories inside Stations & staff. Not primary navigation. */
enum class StationsSegment { STATIONS, STAFF }

/**
 * Stations & staff. One destination with two local segments, so the app keeps exactly
 * three primary tabs.
 */
@Composable
fun StationsAndStaffScreen(
    state: SubdivisionUiState,
    busy: Boolean,
    mutationError: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onSaveStation: (String?, String, String, String, (String) -> Unit) -> Unit,
    onSaveStaff: (String, String?, String, Boolean, Boolean, () -> Unit) -> Unit,
    onOpenStation: (String) -> Unit,
    onOpenStaff: (String) -> Unit,
    onAddContact: () -> Unit,
) {
    var segment by rememberSaveable { mutableStateOf(StationsSegment.STATIONS) }
    var query by rememberSaveable { mutableStateOf("") }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var addingStation by remember { mutableStateOf(false) }
    var pickingContact by remember { mutableStateOf(false) }
    var staffEditorId by remember { mutableStateOf<String?>(null) }

    SubdivisionScaffold("Stations & staff", state, onBack, onRetry) {
        Column(ReadableColumn.fillMaxSize()) {
            MutationErrorBanner(mutationError, onDismissError)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                StationsSegment.entries.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = segment == item,
                        onClick = { segment = item; query = "" },
                        shape = SegmentedButtonDefaults.itemShape(index, StationsSegment.entries.size),
                        modifier = Modifier.testTag("segment_${item.name}"),
                        label = { Text(if (item == StationsSegment.STATIONS) "Stations" else "Staff") },
                    )
                }
            }
            when (segment) {
                StationsSegment.STATIONS -> StationsList(
                    state = state,
                    busy = busy,
                    query = query,
                    onQuery = { query = it },
                    showArchived = showArchived,
                    onShowArchived = { showArchived = it },
                    onAddStation = { addingStation = true },
                    onOpenStation = onOpenStation,
                )
                StationsSegment.STAFF -> StaffList(
                    state = state,
                    busy = busy,
                    query = query,
                    onQuery = { query = it },
                    onAddFromContacts = { pickingContact = true },
                    onAddContact = onAddContact,
                    onOpenStaff = onOpenStaff,
                )
            }
        }

        if (addingStation) {
            StationEditor(
                station = null,
                busy = busy,
                onDismiss = { addingStation = false },
                onSave = { name, kind, notes ->
                    onSaveStation(null, name, kind, notes) { addingStation = false }
                },
            )
        }
        if (pickingContact) {
            val candidates = state.contacts.filterNot { it.isStaff }
            SearchableChooser(
                title = "Add from contacts",
                supporting = "Choose an existing contact to mark as staff. Their name and phone number stay as they are.",
                items = candidates,
                label = { it.name },
                detail = { listOfNotNull(it.designation, it.station).joinToString(" · ").ifBlank { null } },
                onPick = { pickingContact = false; staffEditorId = it.id },
                onDismiss = { pickingContact = false },
                emptyMessage = if (state.contacts.isEmpty()) {
                    "You have no contacts yet. Add or import a contact first, then mark them as staff."
                } else {
                    "No matching contact. Everyone matching your search is already staff."
                },
                searchHint = "Search contacts",
            )
        }
        state.contact(staffEditorId)?.let { person ->
            StaffEditor(
                person = person,
                stations = state.stations,
                busy = busy,
                onDismiss = { staffEditorId = null },
                onSave = { stationId, responsibilities, isStaff, active ->
                    onSaveStaff(person.id, stationId, responsibilities, isStaff, active) { staffEditorId = null }
                },
            )
        }
    }
}

@Composable
private fun StationsList(
    state: SubdivisionUiState,
    busy: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    showArchived: Boolean,
    onShowArchived: (Boolean) -> Unit,
    onAddStation: () -> Unit,
    onOpenStation: (String) -> Unit,
) {
    val rows = remember(state, showArchived, query) { stationRows(state, showArchived, query) }
    val archivedCount = state.stations.count { it.archived }
    LazyColumn(Modifier.fillMaxSize().testTag("stations_list"), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SubdivisionSearch(query, onQuery, "Search stations and units", "stations_search")
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        "${rows.size} ${if (showArchived) "archived" else "active"} " +
                            if (rows.size == 1) "record" else "records",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onAddStation,
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("add_station"),
                    ) {
                        Text("Add station / unit")
                    }
                }
                if (archivedCount > 0 || showArchived) {
                    Row(Modifier.padding(horizontal = 12.dp)) {
                        FilterChip(
                            selected = showArchived,
                            onClick = { onShowArchived(!showArchived) },
                            label = { Text("Archived ($archivedCount)") },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("stations_archived_filter"),
                        )
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            item {
                SubdivisionNotice(
                    title = when {
                        query.isNotBlank() -> "No matching station or unit"
                        showArchived -> "Nothing archived"
                        else -> "Add the stations and units you work with"
                    },
                    body = when {
                        query.isNotBlank() -> "Try part of the name, the unit type, or clear the search."
                        showArchived -> "Archived stations stay here so their history is never lost. " +
                            "You can reopen one at any time."
                        else -> "A station or unit is a place work belongs to — a police station, an outpost, " +
                            "a wing or a cell. Instructions keep the station they were recorded at, so a " +
                            "transfer never rewrites where past work was carried out."
                    },
                    action = if (query.isNotBlank()) "Clear search" else if (showArchived) null else "Add station / unit",
                    onAction = if (query.isNotBlank()) ({ onQuery("") }) else onAddStation,
                    testTag = "stations_empty",
                )
            }
        } else {
            items(rows, key = { it.id }) { row ->
                SubdivisionListRow(
                    headline = row.station.name,
                    supporting = listOf(
                        row.station.kind,
                        "${row.activeStaff} active staff · ${row.openInstructions} open " +
                            if (row.openInstructions == 1) "instruction" else "instructions",
                    ),
                    onClick = { onOpenStation(row.id) },
                    testTag = "station_row_${row.station.name}",
                    trailing = if (row.station.archived) "Archived" else null,
                )
            }
        }
    }
}

@Composable
private fun StaffList(
    state: SubdivisionUiState,
    busy: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onAddFromContacts: () -> Unit,
    onAddContact: () -> Unit,
    onOpenStaff: (String) -> Unit,
) {
    val rows = remember(state, query) { staffRows(state, query) }
    LazyColumn(Modifier.fillMaxSize().testTag("staff_list"), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SubdivisionSearch(query, onQuery, "Search staff, rank or responsibilities", "staff_search")
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        "${rows.size} ${if (rows.size == 1) "officer" else "officers"}",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = if (state.contacts.isEmpty()) onAddContact else onAddFromContacts,
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("add_from_contacts"),
                    ) {
                        Text(if (state.contacts.isEmpty()) "Add a contact first" else "Add from contacts")
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            item {
                SubdivisionNotice(
                    title = if (query.isNotBlank()) "No matching staff officer" else "Mark your staff from your contacts",
                    body = if (query.isNotBlank()) {
                        "Try their name, rank, station or a word from their responsibilities."
                    } else if (state.contacts.isEmpty()) {
                        "Add or import a contact first. Marking somebody as staff records their posting and " +
                            "responsibilities; it does not create an account for them and sends them nothing."
                    } else {
                        "Your contacts stay ordinary contacts until you mark them as staff. Marking somebody " +
                            "records their posting and responsibilities — it does not create an account for " +
                            "them and sends them nothing."
                    },
                    action = if (query.isNotBlank()) {
                        "Clear search"
                    } else if (state.contacts.isEmpty()) {
                        "Add a contact first"
                    } else {
                        "Add from contacts"
                    },
                    onAction = when {
                        query.isNotBlank() -> ({ onQuery("") })
                        state.contacts.isEmpty() -> onAddContact
                        else -> onAddFromContacts
                    },
                    testTag = "staff_empty",
                )
            }
        } else {
            items(rows, key = { it.person.id }) { row ->
                SubdivisionListRow(
                    headline = row.person.name,
                    supporting = listOfNotNull(
                        row.person.designation?.takeIf { it.isNotBlank() },
                        row.stationName ?: "No station assigned",
                        "${row.openInstructions} open " + if (row.openInstructions == 1) "instruction" else "instructions",
                    ),
                    onClick = { onOpenStaff(row.person.id) },
                    testTag = "staff_row_${row.person.name}",
                    trailing = if (row.person.staffActive) "Active" else "Inactive",
                )
            }
        }
    }
}

/** One station's detail: notes, active staff, matter links and the station's own work list. */
@Composable
fun StationDetailScreen(
    stationId: String,
    state: SubdivisionUiState,
    busy: Boolean,
    mutationError: String?,
    today: LocalDate,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onSaveStation: (String?, String, String, String, (String) -> Unit) -> Unit,
    onArchiveStation: (String, Boolean) -> Unit,
    onOpenStaff: (String) -> Unit,
    onOpenMatter: (String) -> Unit,
    onOpenInstruction: (String) -> Unit,
    onReviewStation: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val station = state.station(stationId)
    SubdivisionScaffold(station?.name ?: "Station", state, onBack, onRetry) {
        if (station == null) {
            SubdivisionNoticeScreen(
                title = "This station is no longer available",
                body = "It may have been removed in another workspace. Go back to Stations & staff to pick another.",
                action = "Back",
                onAction = onBack,
                testTag = "station_missing",
            )
            return@SubdivisionScaffold
        }
        val staff = state.contacts.filter { it.stationId == station.id && it.isStaff }
        val matters = state.matters.filter { it.stationId == station.id }
        val work = state.instructions.filter { it.stationId == station.id }
        val open = work.filter { with(com.kaavalan.note.data.subdivision.SubdivisionProjections) { it.isOpenWork } }
        LazyColumn(
            ReadableColumn.fillMaxSize().testTag("station_detail"),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { MutationErrorBanner(mutationError, onDismissError) }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(station.kind, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (station.archived) {
                        Text(
                            "Archived. Its history is intact and you can reopen it at any time.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (station.notes.isNotBlank()) {
                        Text(station.notes, style = MaterialTheme.typography.bodyLarge)
                    }
                    FlowActions {
                        OutlinedButton(
                            onClick = { editing = true },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("station_edit"),
                        ) {
                            Text("Edit station")
                        }
                        OutlinedButton(
                            onClick = { onArchiveStation(station.id, !station.archived) },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("station_archive"),
                        ) {
                            Text(if (station.archived) "Reopen" else "Archive")
                        }
                        TextButton(
                            onClick = { onReviewStation(station.id) },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("station_review"),
                        ) {
                            Text("Review this station")
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    SubdivisionSectionHeading(
                        "Staff",
                        if (staff.isEmpty()) "No staff posted here yet." else "${staff.count { it.staffActive }} active.",
                    )
                }
            }
            items(staff, key = { "staff:${it.id}" }) { person ->
                SubdivisionListRow(
                    headline = person.name,
                    supporting = listOfNotNull(
                        person.designation?.takeIf { it.isNotBlank() },
                        person.responsibilities.takeIf { it.isNotBlank() },
                    ),
                    onClick = { onOpenStaff(person.id) },
                    testTag = "station_staff_${person.name}",
                    trailing = if (person.staffActive) "Active" else "Inactive",
                )
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    SubdivisionSectionHeading(
                        "Matters",
                        if (matters.isEmpty()) "No matters recorded for this station." else null,
                    )
                }
            }
            items(matters, key = { "matter:${it.id}" }) { matter ->
                SubdivisionListRow(
                    headline = matter.title,
                    supporting = listOfNotNull(matter.reference.takeIf { it.isNotBlank() }),
                    onClick = { onOpenMatter(matter.id) },
                    testTag = "station_matter_${matter.title}",
                    trailing = if (matter.archived) "Archived" else null,
                )
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    SubdivisionSectionHeading(
                        "Work recorded here",
                        "${open.size} open of ${work.size} recorded. Work keeps this station even after a transfer.",
                    )
                }
            }
            items(work, key = { "work:${it.id}" }) { item ->
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WorkCard(item, state.contacts, today, onClick = { onOpenInstruction(item.id) })
                    RecordedContextLine(state, item)
                }
            }
            if (work.isEmpty()) {
                item {
                    SubdivisionNotice(
                        title = "No work recorded at this station yet",
                        body = "Instructions you capture for staff posted here will appear in this list.",
                        action = null,
                        onAction = {},
                        testTag = "station_no_work",
                    )
                }
            }
        }
        if (editing) {
            StationEditor(
                station = station,
                busy = busy,
                onDismiss = { editing = false },
                onSave = { name, kind, notes ->
                    onSaveStation(station.id, name, kind, notes) { editing = false }
                },
            )
        }
    }
}

/** One staff officer's detail: identity, posting, responsibilities and dated posting history. */
@Composable
fun StaffDetailScreen(
    personId: String,
    state: SubdivisionUiState,
    busy: Boolean,
    mutationError: String?,
    today: LocalDate,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onSaveStaff: (String, String?, String, Boolean, Boolean, () -> Unit) -> Unit,
    onOpenContact: (String) -> Unit,
    onOpenInstruction: (String) -> Unit,
    onReviewOfficer: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val person = state.contact(personId)
    SubdivisionScaffold(person?.name ?: "Staff officer", state, onBack, onRetry) {
        if (person == null) {
            SubdivisionNoticeScreen(
                title = "This contact is no longer available",
                body = "It may belong to another workspace. Go back to Stations & staff to pick another.",
                action = "Back",
                onAction = onBack,
                testTag = "staff_missing",
            )
            return@SubdivisionScaffold
        }
        val history = remember(state.postings, personId) { postingHistory(state, personId) }
        val work = state.instructions.filter { it.personId == person.id }
        LazyColumn(
            ReadableColumn.fillMaxSize().testTag("staff_detail"),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { MutationErrorBanner(mutationError, onDismissError) }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOfNotNull(person.designation, person.phone).takeIf { it.isNotEmpty() }?.let {
                        Text(
                            it.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        state.station(person.stationId)?.name ?: "No station assigned",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("staff_detail_station"),
                    )
                    Text(
                        if (person.staffActive) "Posting active" else "Posting inactive. History stays available.",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    SubdivisionSectionHeading("Responsibilities")
                    Text(
                        person.responsibilities.takeIf { it.isNotBlank() } ?: "No responsibilities recorded yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("staff_detail_responsibilities"),
                    )
                    FlowActions {
                        OutlinedButton(
                            onClick = { editing = true },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("staff_edit"),
                        ) {
                            Text(if (person.isStaff) "Edit staff details" else "Add to staff")
                        }
                        OutlinedButton(
                            onClick = { onOpenContact(person.id) },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("staff_open_contact"),
                        ) {
                            Text("Edit contact details")
                        }
                        if (person.isStaff) {
                            TextButton(
                                onClick = { onReviewOfficer(person.id) },
                                enabled = !busy,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("staff_review"),
                            ) {
                                Text("Review this officer")
                            }
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    SubdivisionSectionHeading(
                        "Posting history",
                        if (history.isEmpty()) "No recorded posting changes yet." else
                            "Dated record of station and responsibility changes. Never overwritten.",
                    )
                }
            }
            items(history, key = { it.id }) { posting ->
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp).testTag("posting_${posting.id}"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(formatDayTime(posting.recordedAt), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${posting.fromStation.ifBlank { "No station" }} → ${posting.toStation.ifBlank { "No station" }}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(posting.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    SubdivisionSectionHeading(
                        "Their instructions",
                        "Each instruction keeps the station it was recorded at, not this officer's current posting.",
                    )
                }
            }
            items(work, key = { "work:${it.id}" }) { item ->
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WorkCard(item, state.contacts, today, onClick = { onOpenInstruction(item.id) })
                    RecordedContextLine(state, item)
                }
            }
            if (work.isEmpty()) {
                item {
                    SubdivisionNotice(
                        title = "No instructions linked yet",
                        body = "Instructions you link to this officer will appear here.",
                        action = null,
                        onAction = {},
                        testTag = "staff_no_work",
                    )
                }
            }
        }
        if (editing) {
            StaffEditor(
                person = person,
                stations = state.stations,
                busy = busy,
                onDismiss = { editing = false },
                onSave = { stationId, responsibilities, isStaff, active ->
                    onSaveStaff(person.id, stationId, responsibilities, isStaff, active) { editing = false }
                },
            )
        }
    }
}

/**
 * A wrapping row of actions. Wrapping matters: at a 2x font scale three buttons will not
 * fit on one line of a small phone, and a clipped action is an unusable one.
 *
 * Takes a plain composable lambda rather than a `FlowRowScope` one so callers do not each
 * have to opt in to the experimental layout scope.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FlowActions(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

