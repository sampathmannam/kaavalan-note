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
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.subdivision.SubdivisionProjections
import com.kaavalan.note.ui.workspace.WorkCard
import com.kaavalan.note.ui.workspace.isClosed
import java.time.LocalDate

/** The lifecycle filters offered on one matter's instruction list. */
enum class MatterWorkFilter { OPEN, READY_TO_VERIFY, CLOSED, ALL }

private fun matterFilterLabel(filter: MatterWorkFilter) = when (filter) {
    MatterWorkFilter.OPEN -> "Open"
    MatterWorkFilter.READY_TO_VERIFY -> "Ready to verify"
    MatterWorkFilter.CLOSED -> "Closed"
    MatterWorkFilter.ALL -> "All"
}

/**
 * The Matters list. A matter groups related instructions and their history — it is not a
 * legal record and carries no fields beyond a title, an optional station, an optional
 * reference and context.
 */
@Composable
fun MattersScreen(
    state: SubdivisionUiState,
    busy: Boolean,
    mutationError: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onSaveMatter: (String?, String, String?, String, String, (String) -> Unit) -> Unit,
    onOpenMatter: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val rows = remember(state, showArchived, query) { matterRows(state, showArchived, query) }
    val archivedCount = state.matters.count { it.archived }

    SubdivisionScaffold("Matters", state, onBack, onRetry) {
        LazyColumn(
            ReadableColumn.fillMaxSize().testTag("matters_list"),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item { MutationErrorBanner(mutationError, onDismissError) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Group related instructions and their history.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    SubdivisionSearch(query, { query = it }, "Search matters and references", "matters_search")
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            "${rows.size} ${if (showArchived) "archived" else "active"} " +
                                if (rows.size == 1) "matter" else "matters",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { adding = true },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("add_matter"),
                        ) {
                            Text("Add matter")
                        }
                    }
                    if (archivedCount > 0 || showArchived) {
                        Row(Modifier.padding(horizontal = 12.dp)) {
                            FilterChip(
                                selected = showArchived,
                                onClick = { showArchived = !showArchived },
                                label = { Text("Archived ($archivedCount)") },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("matters_archived_filter"),
                            )
                        }
                    }
                }
            }
            if (rows.isEmpty()) {
                item {
                    SubdivisionNotice(
                        title = when {
                            query.isNotBlank() -> "No matching matter"
                            showArchived -> "Nothing archived"
                            else -> "Group work that belongs together"
                        },
                        body = when {
                            query.isNotBlank() -> "Try part of the title or the reference, or clear the search."
                            showArchived -> "Archived matters keep their instructions and history. Reopen one at any time."
                            else -> "A matter is a named group of instructions — an inquiry, a drive, a recurring " +
                                "duty. Add one, then link instructions to it or capture new ones in its context."
                        },
                        action = if (query.isNotBlank()) "Clear search" else if (showArchived) null else "Add matter",
                        onAction = if (query.isNotBlank()) ({ query = "" }) else ({ adding = true }),
                        testTag = "matters_empty",
                    )
                }
            } else {
                items(rows, key = { it.id }) { row ->
                    SubdivisionListRow(
                        headline = row.matter.title,
                        supporting = listOfNotNull(
                            listOfNotNull(
                                row.matter.reference.takeIf { it.isNotBlank() },
                                row.stationName ?: "Subdivision-wide",
                            ).joinToString(" · "),
                            "${row.openInstructions} open " +
                                if (row.openInstructions == 1) "instruction" else "instructions",
                        ),
                        onClick = { onOpenMatter(row.id) },
                        testTag = "matter_row_${row.matter.title}",
                        trailing = if (row.matter.archived) "Archived" else null,
                    )
                }
            }
        }
        if (adding) {
            MatterEditor(
                matter = null,
                stations = state.stations,
                hasLinkedInstructions = false,
                busy = busy,
                onDismiss = { adding = false },
                onSave = { title, stationId, reference, description ->
                    onSaveMatter(null, title, stationId, reference, description) { adding = false }
                },
            )
        }
    }
}

/**
 * One matter's detail: its context, its instructions with lifecycle filters, and the two
 * ways to add work — link an existing instruction, or capture a new one with this matter
 * already selected. There is no parallel "New task" form; new work goes through the
 * existing capture flow.
 */
@Composable
fun MatterDetailScreen(
    matterId: String,
    state: SubdivisionUiState,
    busy: Boolean,
    mutationError: String?,
    today: LocalDate,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onSaveMatter: (String?, String, String?, String, String, (String) -> Unit) -> Unit,
    onArchiveMatter: (String, Boolean) -> Unit,
    onLinkInstruction: (String, String?, String?, () -> Unit) -> Unit,
    onCaptureInContext: (String?, String?, String) -> Unit,
    onOpenInstruction: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(MatterWorkFilter.OPEN) }
    val matter = state.matter(matterId)

    SubdivisionScaffold(matter?.title ?: "Matter", state, onBack, onRetry) {
        if (matter == null) {
            SubdivisionNoticeScreen(
                title = "This matter is no longer available",
                body = "Go back to Matters to pick another.",
                action = "Back",
                onAction = onBack,
                testTag = "matter_missing",
            )
            return@SubdivisionScaffold
        }
        val linked = state.instructions.filter { it.matterId == matter.id }
        val open = linked.filter { with(SubdivisionProjections) { it.isOpenWork } }
        val results = when (filter) {
            MatterWorkFilter.OPEN -> open
            MatterWorkFilter.READY_TO_VERIFY -> linked.filter { with(SubdivisionProjections) { it.isReadyToVerify } }
            MatterWorkFilter.CLOSED -> linked.filter { it.isClosed }
            MatterWorkFilter.ALL -> linked
        }
        val stationName = state.station(matter.stationId)?.name

        LazyColumn(
            ReadableColumn.fillMaxSize().testTag("matter_detail"),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { MutationErrorBanner(mutationError, onDismissError) }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        listOfNotNull(
                            matter.reference.takeIf { it.isNotBlank() },
                            stationName ?: "Subdivision-wide",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("matter_detail_meta"),
                    )
                    if (matter.archived) {
                        Text(
                            "Archived. Its instructions and history are intact and you can reopen it at any time.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (matter.description.isNotBlank()) {
                        Text(matter.description, style = MaterialTheme.typography.bodyLarge)
                    }
                    FlowActions {
                        Button(
                            onClick = {
                                onCaptureInContext(
                                    matter.stationId,
                                    matter.id,
                                    listOfNotNull(matter.title, stationName).joinToString(" · "),
                                )
                            },
                            enabled = !busy && !matter.archived,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("matter_add_instruction"),
                        ) {
                            Text("Add instruction")
                        }
                        OutlinedButton(
                            onClick = { linking = true },
                            enabled = !busy && !matter.archived,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("matter_link_instruction"),
                        ) {
                            Text("Link instruction")
                        }
                        OutlinedButton(
                            onClick = { editing = true },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("matter_edit"),
                        ) {
                            Text("Edit matter")
                        }
                        TextButton(
                            onClick = { onArchiveMatter(matter.id, !matter.archived) },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("matter_archive"),
                        ) {
                            Text(if (matter.archived) "Reopen" else "Archive")
                        }
                    }
                    if (matter.archived) {
                        Text(
                            "Reopen this matter to add or link more instructions.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubdivisionSectionHeading(
                        "Instructions",
                        "${open.size} open of ${linked.size} linked.",
                    )
                    MatterFilterRow(filter) { filter = it }
                }
            }
            if (results.isEmpty()) {
                item {
                    SubdivisionNotice(
                        title = if (linked.isEmpty()) "No instructions linked yet" else "Nothing in this view",
                        body = if (linked.isEmpty()) {
                            "Use Add instruction to capture new work with this matter already selected, or " +
                                "Link instruction to attach something you have already recorded."
                        } else {
                            "No linked instruction matches \"${matterFilterLabel(filter)}\" right now."
                        },
                        action = null,
                        onAction = {},
                        testTag = "matter_no_work",
                    )
                }
            } else {
                items(results, key = { "linked:${it.id}" }) { item ->
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        WorkCard(item, state.contacts, today, onClick = { onOpenInstruction(item.id) })
                        RecordedContextLine(state, item)
                    }
                }
            }
        }

        if (editing) {
            MatterEditor(
                matter = matter,
                stations = state.stations,
                hasLinkedInstructions = linked.isNotEmpty(),
                busy = busy,
                onDismiss = { editing = false },
                onSave = { title, stationId, reference, description ->
                    onSaveMatter(matter.id, title, stationId, reference, description) { editing = false }
                },
            )
        }
        if (linking) {
            // A station-specific matter may only take work already recorded at that station;
            // a subdivision-wide matter may span stations.
            val candidates = state.instructions.filter { item ->
                item.matterId != matter.id &&
                    (matter.stationId == null || item.stationId == matter.stationId)
            }
            SearchableChooser(
                title = "Link an instruction",
                supporting = if (matter.stationId == null) {
                    "This matter is subdivision-wide, so it can take work from any station."
                } else {
                    "Only work already recorded at ${stationName ?: "this station"} can be linked to this matter. " +
                        "Change an instruction's work context first if you need to move it."
                },
                items = candidates,
                label = { it.rawText.ifBlank { it.title }.take(80) },
                detail = { linkCandidateDetail(state, it) },
                onPick = { picked ->
                    onLinkInstruction(picked.id, matter.stationId ?: picked.stationId, matter.id) { linking = false }
                },
                onDismiss = { linking = false },
                emptyMessage = if (matter.stationId == null) {
                    "No other instruction to link yet. Use Add instruction to capture new work in this matter."
                } else {
                    "No instruction is recorded at ${stationName ?: "this station"} yet. Use Add instruction, " +
                        "or change an existing instruction's work context first."
                },
                searchHint = "Search instructions",
            )
        }
    }
}

private fun linkCandidateDetail(state: SubdivisionUiState, item: Instruction): String {
    val context = workContextOf(state, item)
    return listOfNotNull(
        state.contact(item.personId)?.name,
        context.stationName ?: "No station recorded",
        context.matterTitle?.let { "already in $it" },
    ).joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MatterFilterRow(selected: MatterWorkFilter, onSelect: (MatterWorkFilter) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MatterWorkFilter.entries.forEach { item ->
            FilterChip(
                selected = selected == item,
                onClick = { onSelect(item) },
                label = { Text(matterFilterLabel(item)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("matter_filter_${item.name}"),
            )
        }
    }
}
