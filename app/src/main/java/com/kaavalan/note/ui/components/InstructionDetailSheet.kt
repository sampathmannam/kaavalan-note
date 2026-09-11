package com.kaavalan.note.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kaavalan.note.data.instructions.*
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.features.reminder.ReminderPicker
import com.kaavalan.note.features.reminder.formatReminderTime
import com.kaavalan.note.ui.workspace.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One detail contract across Today, Instructions and a contact's timeline. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InstructionDetailSheet(
    instruction: Instruction,
    onDismiss: () -> Unit,
    onMarkDone: () -> Unit,
    onDrop: () -> Unit,
    onReopen: () -> Unit,
    onReminderChanged: (Long?) -> Unit,
    busy: Boolean = false,
    contactName: String? = null,
    onShare: (() -> Unit)? = null,
    onPrivacy: (() -> Unit)? = null,
    contacts: List<Person> = emptyList(),
    privateMode: Boolean = false,
    onEdit: ((String, Direction, String?, Long?, () -> Unit) -> Unit)? = null,
    onAddUpdate: ((String, Status, Long?, () -> Unit) -> Unit)? = null,
    // v2.6.0 (subdivision CRM): the station and matter recorded ON THIS INSTRUCTION.
    // Deliberately not the assigned contact's current station — an officer's transfer must
    // not appear to relocate work they finished last month. Null in the private workspace,
    // which is how the context row stays out of it.
    workContextStation: String? = null,
    workContextMatter: String? = null,
    workContextReference: String? = null,
    onChangeWorkContext: (() -> Unit)? = null,
) {
    var confirmClose by remember { mutableStateOf(false) }
    var editing by rememberSaveable(instruction.id) { mutableStateOf(false) }
    var adding by rememberSaveable(instruction.id) { mutableStateOf(false) }
    var showReminder by rememberSaveable(instruction.id) { mutableStateOf(false) }
    if (editing && onEdit != null) {
        InstructionEditSheet(instruction, contacts, privateMode, busy, { editing = false }) { text, direction, person, deadline ->
            onEdit(text, direction, person, deadline) { editing = false }
        }
        return
    }
    if (adding && onAddUpdate != null) {
        InstructionUpdateSheet(instruction, busy, { adding = false }) { text, status, followUp ->
            onAddUpdate(text, status, followUp) { adding = false }
        }
        return
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        com.kaavalan.note.ui.theme.DialogSystemBars()
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(horizontal = 20.dp)) {
            Text("Instruction", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(instruction.status.officerLabel(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(listOfNotNull(instruction.direction.officerLabel(), contactName ?: instruction.audience?.label).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(instruction.rawText.ifBlank { instruction.title }, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("instruction_detail_text"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onEdit != null) TextButton(onClick = { editing = true }, enabled = !busy, modifier = Modifier.testTag("detail_edit")) { Text("Edit instruction") }
                    onShare?.let { TextButton(onClick = it, enabled = !busy) { Text("Share follow-up") } }
                    onPrivacy?.let { TextButton(onClick = it, enabled = !busy) { Text("Privacy") } }
                }
                if (onChangeWorkContext != null) {
                    HorizontalDivider()
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.testTag("instruction_work_context"),
                    ) {
                        Text("Station & matter", style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOfNotNull(
                                workContextStation ?: "No station recorded",
                                workContextMatter ?: "No matter",
                                workContextReference,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Where this instruction is filed. Reassigning the contact does not change it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = onChangeWorkContext,
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("detail_change_context"),
                        ) {
                            Text("Change work context")
                        }
                    }
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Deadline", style = MaterialTheme.typography.titleSmall)

                    Text(instruction.deadlineAtMs?.let { formatReminderTime(it) } ?: "No deadline set", style = MaterialTheme.typography.bodyLarge)
                    Text(if (instruction.isClosed) "Previous follow-up · reminder stopped" else "Next follow-up", style = MaterialTheme.typography.titleSmall)
                    Text(instruction.reminderMillis?.let { formatReminderTime(it) } ?: "No follow-up scheduled", style = MaterialTheme.typography.bodyLarge)
                    if (!instruction.isClosed) TextButton(onClick = { showReminder = !showReminder }, enabled = !busy) { Text(if (showReminder) "Hide reminder choices" else "Change follow-up") }
                    if (showReminder && !instruction.isClosed && !busy) ReminderPicker(instruction.reminderMillis, onReminderChanged,
                        title = "Next follow-up", supportingText = "This reminds you to check again. The deadline stays unchanged.")
                }
                HorizontalDivider()
                Text("Updates", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text("Private notes, not messages to the contact.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (instruction.updates.isEmpty()) Text("No updates yet. After a call or review, record what happened and when to check again.", style = MaterialTheme.typography.bodyLarge)
                instruction.updates.asReversed().forEach { update ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("update_${update.id}")) {
                        Text(formatTimeIso(update.at), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(update.text, style = MaterialTheme.typography.bodyLarge)
                        val progress = runCatching { Status.valueOf(update.status).officerLabel() }.getOrDefault(update.status)
                        Text(progress, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        update.nextFollowUpAtMs?.let { Text("Follow-up set for ${formatReminderTime(it)}", style = MaterialTheme.typography.bodyMedium) }
                        if (update.previousText != null) {
                            var expanded by rememberSaveable(update.id) { mutableStateOf(false) }
                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide previous text" else "View previous text") }
                            if (expanded) Text(update.previousText, style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
                Text("Captured ${formatTimeIso(instruction.capturedAt)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (instruction.isClosed) Text(if (instruction.status == Status.DONE) "Completed ${instruction.completedAt?.let(::formatTimeIso).orEmpty()}" else "Closed without action",
                    style = MaterialTheme.typography.bodyMedium)
                else TextButton(onClick = { confirmClose = true }, enabled = !busy) { Text("Close without action") }
            }
            HorizontalDivider(Modifier.padding(top = 12.dp))
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (instruction.isClosed) Button(onClick = onReopen, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("detail_reopen")) { Text("Reopen") }
                else {
                    if (onAddUpdate != null) Button(onClick = { adding = true }, enabled = !busy, modifier = Modifier.testTag("detail_add_update")) { Text("Add update") }
                    OutlinedButton(onClick = onMarkDone, enabled = !busy, modifier = Modifier.testTag("detail_done")) {
                        Text(if (instruction.status == Status.REPORTED_DONE) "Verify & mark done" else "Mark done")
                    }
                }
            }
        }
    }
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("Close without action?") },
        text = { Text("This stays in Instructions → Closed. You can reopen it later.") },
        confirmButton = { TextButton(onClick = { confirmClose = false; onDrop() }, enabled = !busy) { Text("Close instruction") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep open") } })
}

internal fun formatTimeIso(iso: String): String = try {
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
} catch (_: Exception) { iso }
