package com.kaavalan.note.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaavalan.note.R
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.Status
import com.kaavalan.note.ui.workspace.WorkCard
import com.kaavalan.note.ui.workspace.isClosed
import com.kaavalan.note.ui.components.InstructionDetailSheet
import androidx.compose.runtime.saveable.rememberSaveable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * M3-T6: Person detail screen. Shows the timeline of all
 * instructions for one person — both incoming (they gave it to
 * the user) and outgoing (the user gave it to them). Sorted by
 * `capturedAt DESC` (most recent first) so the most recent
 * exchange is at the top.
 *
 * **No navigation graph yet.** For M3 the detail screen is
 * reached by tapping a row on the Home tab; the
 * [com.kaavalan.note.ui.home.HomeScreen] composable manages a
 * `selectedPersonId` state that flips when a row is tapped and
 * triggers this composable. When M3.5 lands a real nav graph
 * (Today tab, deep links) the wiring moves into the
 * `composable("person/{id}")` entry — the ViewModel contract
 * here (`observeForPerson(id)`) is the same.
 *
 * **Status chip.** Each instruction row carries a small label
 * for its status (`OPEN`, `DONE`, `CARRIED_OVER`, etc.). The
 * design rule (spec §3.3) is "no shame language": `CARRIED_OVER`
 * is used in place of `OVERDUE`; the chip colour is the
 * `secondaryContainer` (calm) when open, `surfaceVariant` (grey)
 * when closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(
    personId: String,
    onBack: () -> Unit,
    onOpenLinkedPerson: (String) -> Unit = {},
    onCaptureForPerson: ((String) -> Unit)? = null,
    onOpenInstruction: ((String) -> Unit)? = null,
    onEditContact: (() -> Unit)? = null,
    viewModel: PersonDetailViewModel = hiltViewModel(),
) {
    // Hilt's SavedStateHandle lets the VM pick up the `personId`
    // nav arg without us passing it manually. The VM exposes
    // `state` as a Flow that the composable collects.
    val state by viewModel.state.collectAsStateWithLifecycle()
    var nudgeTarget by remember { mutableStateOf<Instruction?>(null) }
    var dropTarget by remember { mutableStateOf<Instruction?>(null) }
    var sensitiveToggleId by remember { mutableStateOf<String?>(null) }
    var showPersonSensitive by remember { mutableStateOf(false) }
    // v1.5.3 (VAULT-003): local "Add instruction" sheet, pre-attributed
    // to this person. Avoids making the user back out to the home
    // tab to capture an instruction for the person they're looking at.
    var showAddInstruction by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        topBar = {
            // v1.7.2 (P1-C): the TopAppBar used to render the
            // person's name, but the body's `PersonHeader` (line
            // 348+) ALSO renders the name as the first row. The
            // result was a duplicated header — the user saw the
            // name twice, once large in the top bar and once as
            // a `headlineSmall` body header above the designation.
            // Now the TopAppBar is just a chevron back icon; the
            // body's PersonHeader is the single source of truth
            // for the person's name + designation.
            TopAppBar(
                title = { Text("Contact", style = MaterialTheme.typography.titleLarge) },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.person_detail_back),
                        )
                    }
                },
                actions = { if (onEditContact != null) TextButton(onClick = onEditContact, enabled = !busy) { Text("Edit contact") } },
            )
        },
    ) { padding ->
        when (val s = state) {
            PersonDetailUiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { androidx.compose.material3.CircularProgressIndicator() }
            PersonDetailUiState.Unavailable -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("Contact unavailable", style = MaterialTheme.typography.titleLarge)
                Text("This contact is not available in the current workspace.", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onBack) { Text("Back to workspace") }
            }
            is PersonDetailUiState.Loaded -> PersonTimeline(
                person = s.person,
                instructions = s.instructions,
                padding = padding,
                onNudge = { ins -> nudgeTarget = ins },
                onMarkDone = { ins -> viewModel.markDone(ins.id) },
                onReopen = { ins -> viewModel.reopen(ins.id) },
                onRequestDrop = { ins -> dropTarget = ins },
                onRequestInstructionSensitive = { ins -> sensitiveToggleId = ins.id },
                onOpenPersonSensitive = { showPersonSensitive = true },
                onAddInstruction = { onCaptureForPerson?.invoke(personId) ?: run { showAddInstruction = true } },
                onOpenInstruction = { id ->
                    // Legacy sensitive records remain explicitly accessible from their own contact.
                    if (s.instructions.firstOrNull { it.id == id }?.isSensitive == true || onOpenInstruction == null) selectedId = id
                    else onOpenInstruction(id)
                },
                onOpenLinkedPerson = onOpenLinkedPerson,
            )
        }
    }

    val target = nudgeTarget
    val loaded = (state as? PersonDetailUiState.Loaded)
    loaded?.instructions?.firstOrNull { it.id == selectedId }?.let { item ->
        InstructionDetailSheet(
            instruction = item, contactName = loaded.person.name, busy = busy,
            onDismiss = { selectedId = null },
            onMarkDone = { viewModel.markDone(item.id) { selectedId = null } },
            onDrop = { viewModel.markDropped(item.id, null) { selectedId = null } },
            onReopen = { viewModel.reopen(item.id) { selectedId = null } },
            onReminderChanged = { viewModel.updateReminder(item.id, it) },
            onShare = { selectedId = null; nudgeTarget = item },
            onPrivacy = { selectedId = null; sensitiveToggleId = item.id },
        )
    }
    if (target != null && loaded != null) {
        NudgeSheet(
            instruction = target,
            person = loaded.person,
            onDismiss = { nudgeTarget = null },
        )
    }

    val dropIns = dropTarget
    if (dropIns != null) {
        DropDialog(
            instructionTitle = dropIns.title,
            onConfirm = { reason ->
                viewModel.markDropped(dropIns.id, reason)
                dropTarget = null
            },
            onDismiss = { dropTarget = null },
        )
    }

    val insId = sensitiveToggleId
    val insForSensitive = loaded?.instructions?.firstOrNull { it.id == insId }
    if (insForSensitive != null) {
        InstructionSensitiveDialog(
            instruction = insForSensitive,
            onConfirm = { newValue ->
                viewModel.setInstructionSensitive(insForSensitive.id, newValue)
                sensitiveToggleId = null
            },
            onDismiss = { sensitiveToggleId = null },
        )
    }

    if (showPersonSensitive && loaded != null) {
        PersonSensitiveDialog(
            person = loaded.person,
            onConfirm = { newValue ->
                viewModel.setPersonSensitive(loaded.person.id, newValue)
                showPersonSensitive = false
            },
            onDismiss = { showPersonSensitive = false },
        )
    }

    if (showAddInstruction && loaded != null) {
        AddInstructionForPersonSheet(
            personName = loaded.person.name,
            onSave = { text ->
                viewModel.createInstructionForThisPerson(text)
                showAddInstruction = false
            },
            onDismiss = { showAddInstruction = false },
        )
    }
}

/**
 * v1.5.3 (VAULT-003): a small bottom sheet that captures an
 * instruction pre-attributed to the person the user is
 * looking at. The text field uses `capitalization = Words`
 * and no autocorrect so proper-noun content (names, places)
 * isn't mangled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddInstructionForPersonSheet(
    personName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.person_new_instruction_title, personName),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.person_new_instruction_body, personName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                label = { Text(stringResource(R.string.person_note_label)) },
                // VAULT-004: disable autocorrect + sentence caps
                // for proper-noun content (names, designations).
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                    autoCorrectEnabled = false,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { onSave(text) },
                    enabled = text.trim().isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PersonTimeline(
    person: com.kaavalan.note.data.person.Person,
    instructions: List<Instruction>,
    padding: PaddingValues,
    onNudge: (Instruction) -> Unit = {},
    onMarkDone: (Instruction) -> Unit = {},
    onReopen: (Instruction) -> Unit = {},
    onRequestDrop: (Instruction) -> Unit = {},
    onRequestInstructionSensitive: (Instruction) -> Unit = {},
    onOpenPersonSensitive: () -> Unit = {},
    onAddInstruction: () -> Unit = {},
    onOpenLinkedPerson: (String) -> Unit = {},
    onOpenInstruction: (String) -> Unit = {},
) {
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var showClosed by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        // v1.6.3: 16dp horizontal contentPadding so the
        // cards no longer need their own horizontal padding
        // (full-width clickable hit targets).
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PersonHeader(
                person = person,
                openInstructionCount = instructions.count { !it.isClosed },
                onAddInstruction = onAddInstruction,
                onOpenSensitive = onOpenPersonSensitive,
            )
        }
        // v2.0 Tier 2 (§2.12): person-to-person links.
        item { TextButton(onClick = { showOptions = !showOptions }) { Text(if (showOptions) "Hide contact details" else "Relationships & important dates") } }
        if (showOptions) {
        item { PersonLinksRow(onOpenPerson = onOpenLinkedPerson) }
        // v2.0 Tier 2 (§2.5): important dates per person.
        item { ImportantDatesRow() }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(selected = !showClosed, onClick = { showClosed = false },
                    label = { Text("Open instructions") })
                androidx.compose.material3.FilterChip(selected = showClosed, onClick = { showClosed = true },
                    label = { Text("Closed") })
            }
        }
        if (instructions.none { it.isClosed == showClosed }) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (showClosed) "No closed instructions for this contact. Completed work will stay here." else "No open instructions for this contact.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(items = instructions.filter { it.isClosed == showClosed }, key = { it.id }) { ins ->
                WorkCard(ins, listOf(person), java.time.LocalDate.now(), onClick = { onOpenInstruction(ins.id) })
            }
        }
        // v1.6.3: removed the trailing 80dp Spacer. The
        // LazyColumn's contentPadding(bottom) is the bottom
        // buffer; nothing else lives below the list on this
        // screen.
    }
}

/**
 * v1.1: the person header now includes a single "Sensitive" toggle
 * (spec §13). The row underneath is a quiet `TextButton` (not a
 * `Switch` — switches are too prominent for an ADHD-friendly design
 * and would suggest the row is currently editable). The button label
 * is "Mark as sensitive" / "Keep on this device" depending on state.
 */
@Composable
private fun PersonHeader(
    person: com.kaavalan.note.data.person.Person,
    openInstructionCount: Int,
    onOpenSensitive: () -> Unit,
    onAddInstruction: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // v1.6.3: no horizontal padding here; the
            // LazyColumn's contentPadding handles it.
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = person.name,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val subtitle = listOfNotNull(person.designation, person.station).joinToString(" • ")
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        person.phone?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        // v1.5.3 (VAULT-003): primary "Add instruction" button —
        // the user is on this screen because they want to do
        // something involving this person. Don't make them
        // navigate away to capture.
        // v1.6.3: switched from filled Button to OutlinedButton
        // — the filled variant was the loudest element on the
        // screen and pulled attention away from the timeline.
        // OutlinedButton keeps the action discoverable but
        // lets the instructions read as the primary content.
        androidx.compose.material3.OutlinedButton(
            onClick = onAddInstruction,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.person_add_instruction_for, person.name))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (person.isSensitive) {
                // v1.5.1: vault mode — there's no Supabase sync either
                // way. Sensitive = never leaves the device, even if
                // cloud sync is re-enabled later.
                stringResource(R.string.person_sensitive_explainer_full)
            } else {
                // v1.5.1: vault mode copy. Replaces the pre-vault
                // "Syncs to Supabase..." text that lied to the user
                // (VAULT-001).
                stringResource(R.string.person_sensitive_explainer_short)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenSensitive) {
            Text(
                if (person.isSensitive) stringResource(R.string.person_remove_sensitive_flag)
                else stringResource(R.string.person_mark_as_sensitive)
            )
        }
        Spacer(Modifier.height(8.dp))
        // v1.2 root-cause fix (F-03 in the UI audit): the previous
        // version hardcoded `/* placeholder */ 0` here, so the user
        // always saw "0 instructions" no matter how many were
        // actually open. We now take the live count from the
        // ViewModel and only render the line when the count is
        // non-zero — the spec says "no counts shown when 0".
        if (openInstructionCount > 0) {
            // v1.6.3: use the plural resource (1 instruction vs
            // N instructions). The `pluralStringResource` API
            // reads the `person_detail_timeline_count` <plurals>
            // element and applies the correct quantity string
            // based on the count.
            Text(
                text = androidx.compose.ui.res.pluralStringResource(
                    id = R.plurals.person_detail_timeline_count,
                    count = openInstructionCount,
                    openInstructionCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InstructionRow(
    instruction: Instruction,
    onNudge: (Instruction) -> Unit = {},
    onMarkDone: () -> Unit = {},
    onReopen: () -> Unit = {},
    onRequestDrop: () -> Unit = {},
    onRequestSensitive: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        // v1.6.3: no horizontal padding here; the
        // LazyColumn's contentPadding handles it.
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = instruction.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(instruction.status)
            }
            Text(
                text = instruction.rawText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatCapturedAt(instruction.capturedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (instruction.completedAt != null) {
                Text(
                    text = stringResource(R.string.person_done_at, formatCapturedAt(instruction.completedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (instruction.droppedReason != null) {
                Text(
                    text = stringResource(R.string.person_dropped_reason, instruction.droppedReason),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            InstructionActions(
                instruction = instruction,
                onNudge = { onNudge(instruction) },
                onMarkDone = onMarkDone,
                onReopen = onReopen,
                onRequestDrop = onRequestDrop,
                onRequestSensitive = onRequestSensitive,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun InstructionActions(
    instruction: Instruction,
    onNudge: () -> Unit,
    onMarkDone: () -> Unit,
    onReopen: () -> Unit,
    onRequestDrop: () -> Unit,
    onRequestSensitive: () -> Unit,
) {
    val isClosed = instruction.status == Status.DONE || instruction.status == Status.DROPPED
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (isClosed) {
            TextButton(onClick = onReopen) { Text(stringResource(R.string.action_reopen)) }
        } else {
            if (instruction.direction == com.kaavalan.note.data.instructions.Direction.OUTGOING) {
                TextButton(onClick = onNudge) { Text(stringResource(R.string.action_draft_nudge)) }
            }
            TextButton(onClick = onMarkDone) { Text(stringResource(R.string.action_mark_done)) }
            TextButton(onClick = onRequestDrop) { Text(stringResource(R.string.action_drop)) }
        }
        TextButton(onClick = onRequestSensitive) {
            Text(
                if (instruction.isSensitive) stringResource(R.string.instruction_make_syncable)
                else stringResource(R.string.instruction_mark_sensitive)
            )
        }
    }
}

/**
 * v1.1: drop dialog. Captures an optional `reason` and confirms
 * before calling the VM. Reason is preserved on the row (server-side
 * too) so the user can review what was dropped in a future conflict
 * UI.
 */
@Composable
private fun DropDialog(
    instructionTitle: String,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.person_drop_title)) },
        text = {
            Column {
                Text("\"$instructionTitle\"")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(200) },
                    label = { Text(stringResource(R.string.person_drop_reason_label)) },
                    singleLine = false,
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason.ifBlank { null }) }) { Text(stringResource(R.string.action_drop)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * v1.1: confirm dialog before flipping the row's `is_sensitive`
 * flag. Toggling on is the more impactful action (the row never
 * reaches the server again) so it gets a confirmation; toggling
 * off is fine to do in one tap.
 */
@Composable
private fun InstructionSensitiveDialog(
    instruction: Instruction,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val newValue = !instruction.isSensitive
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (newValue) stringResource(R.string.instruction_mark_sensitive)
                else stringResource(R.string.instruction_make_syncable)
            )
        },
        text = {
            Text(
                if (newValue) {
                    // v1.5.1: vault-mode copy. Sensitive = never leaves
                    // the device, even if cloud sync is re-enabled later.
                    stringResource(R.string.instruction_will_stay_local)
                } else {
                    // v1.5.1: vault-mode copy. The unsensitive path
                    // doesn't actually re-enable Supabase sync in
                    // vault mode — instructions still stay local.
                    stringResource(
                        R.string.instruction_will_start_syncing,
                        stringResource(R.string.instruction_will_start_syncing_tail),
                    )
                }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(newValue) }) {
                Text(
                    if (newValue) stringResource(R.string.instruction_mark_sensitive)
                    else stringResource(R.string.instruction_make_syncable)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * v1.1: confirm dialog before flipping the person's `is_sensitive`
 * flag. Same semantics as [InstructionSensitiveDialog] but for the
 * whole person (which also affects all of their instructions on
 * the next sync).
 */
@Composable
private fun PersonSensitiveDialog(
    person: com.kaavalan.note.data.person.Person,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val newValue = !person.isSensitive
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (newValue) stringResource(R.string.instruction_mark_sensitive)
                else stringResource(R.string.person_remove_sensitive_flag)
            )
        },
        text = {
            Text(
                if (newValue) {
                    // v1.5.1: vault-mode copy. Sensitive = never leaves
                    // the device, even if cloud sync is re-enabled later.
                    stringResource(R.string.person_sensitive_stay_local, person.name)
                } else {
                    // v1.5.1: vault-mode copy.
                    stringResource(R.string.person_sensitive_will_sync, person.name)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(newValue) }) {
                Text(
                    if (newValue) stringResource(R.string.instruction_mark_sensitive)
                    else stringResource(R.string.instruction_remove_flag)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun StatusChip(status: Status) {
    val (bg, fg) = when (status) {
        Status.OPEN, Status.IN_PROGRESS, Status.ACK_PENDING, Status.WAITING_ON_OTHER, Status.REPORTED_DONE ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        Status.DONE ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        Status.CARRIED_OVER ->
            // v1.6.3: changed from tertiaryContainer (pink/coral)
            // to surfaceVariant (calm grey). The spec rule is
            // "no shame language, no red badges" — but the
            // pink tertiaryContainer was almost as loud. Use
            // surfaceVariant (the same as DONE / DROPPED) and
            // let the text "Carried over" carry the meaning.
            // The chip still distinguishes from DONE because
            // of the surrounding context (the row stays
            // actionable) and the label text itself.
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        Status.DROPPED ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = status.name.replace('_', ' ').lowercase()
        .replaceFirstChar { it.uppercase() }
    // v1.3 (F-19): the chip is decorative; the rendered text is
    // already the status name. We add a `Status: …` prefix via
    // semantics so TalkBack announces the role (this is a status
    // label) and not just the bare word "Open" or "Carried over".
    val statusDesc = stringResource(R.string.a11y_status_chip, statusLabel)
    androidx.compose.material3.Surface(
        color = bg,
        contentColor = fg,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        modifier = Modifier.semantics { contentDescription = statusDesc },
    ) {
        Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun formatCapturedAt(iso: String): String {
    return try {
        val instant = Instant.parse(iso)
        val formatter = DateTimeFormatter
            .ofPattern("d MMM, HH:mm")
            .withZone(ZoneId.systemDefault())
        formatter.format(instant)
    } catch (e: Exception) {
        iso
    }
}
