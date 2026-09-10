package com.kaavalan.note.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kaavalan.note.data.instructions.*
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.features.reminder.ReminderPicker

/** Shared editor frame: content scrolls, the save action stays above the keyboard. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceEditor(title: String, busy: Boolean, valid: Boolean, onDismiss: () -> Unit,
    onSave: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var discard by remember { mutableStateOf(false) }
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val compactTyping = landscape && WindowInsets.isImeVisible
    val requestDismiss = { if (!busy) discard = true }
    ModalBottomSheet(onDismissRequest = requestDismiss,
        dragHandle = if (landscape) null else ({ BottomSheetDefaults.DragHandle() }),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        com.kaavalan.note.ui.theme.DialogSystemBars()
        Column(Modifier.fillMaxHeight(.94f).imePadding().padding(horizontal = 20.dp)) {
            if (!compactTyping) Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            HorizontalDivider(Modifier.padding(top = if (compactTyping) 0.dp else 12.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = if (compactTyping) 0.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = requestDismiss, enabled = !busy) { Text("Cancel") }
                Button(onClick = onSave, enabled = valid && !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("workspace_editor_save")) {
                    Text(if (busy) "Saving…" else "Save")
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard changes?") },
        text = { Text("Your saved instruction and earlier updates will not change.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstructionEditSheet(instruction: Instruction, contacts: List<Person>, privateMode: Boolean,
    busy: Boolean, onDismiss: () -> Unit, onSave: (String, Direction, String?, Long?) -> Unit) {
    var text by rememberSaveable(instruction.id) { mutableStateOf(instruction.rawText) }
    var direction by rememberSaveable(instruction.id) { mutableStateOf(instruction.direction) }
    var personId by rememberSaveable(instruction.id) { mutableStateOf(instruction.personId ?: (instruction.audience as? AudienceRef.ByPerson)?.personId) }
    var deadline by rememberSaveable(instruction.id) { mutableStateOf(instruction.deadlineAtMs) }
    var pickContact by remember { mutableStateOf(false) }
    WorkspaceEditor("Edit instruction", busy, text.isNotBlank() && (!privateMode || personId != null), onDismiss,
        { onSave(text, direction, personId, deadline) }) {
        OutlinedTextField(text, { text = it }, label = { Text("Instruction") }, minLines = 4,
            enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("edit_instruction_text"))
        Text("Responsibility", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Direction.entries.forEach { item -> FilterChip(selected = direction == item, enabled = !busy,
                onClick = { direction = item }, label = { Text(item.officerLabel()) }) }
        }
        OutlinedButton(onClick = { pickContact = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(contacts.firstOrNull { it.id == personId }?.name ?: instruction.audience?.label ?: "Link a contact (optional)")
        }
        if (instruction.audience?.isBroadcast == true) Text("Changing the contact replaces this instruction’s group link. It does not send a message.",
            style = MaterialTheme.typography.bodyMedium)
        ReminderPicker(deadline, { deadline = it }, title = "Deadline", deadline = true,
            supportingText = "When the work must be finished. This does not change your next follow-up.")
    }
    if (pickContact) ContactLinkDialog(contacts, privateMode || instruction.audience?.isBroadcast == true, { personId = it; pickContact = false }, { pickContact = false })
}

@Composable
fun InstructionUpdateSheet(instruction: Instruction, busy: Boolean, onDismiss: () -> Unit,
    onSave: (String, Status, Long?) -> Unit) {
    var text by rememberSaveable(instruction.id) { mutableStateOf("") }
    var status by rememberSaveable(instruction.id) { mutableStateOf(instruction.status.takeIf { it in InstructionWorkflow.progressStates } ?: Status.OPEN) }
    var followUp by rememberSaveable(instruction.id) { mutableStateOf(instruction.reminderMillis) }
    var chooseStatus by remember { mutableStateOf(false) }
    val past = followUp?.let { it <= System.currentTimeMillis() } == true
    WorkspaceEditor("Add update", busy, text.isNotBlank() && !past, onDismiss, { onSave(text, status, followUp) }) {
        Text("A private record of your call, review or follow-up. Nothing is sent to the contact.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(text, { text = it }, enabled = !busy, label = { Text("What happened?") },
            placeholder = { Text("Spoke to the inspector. Report expected tomorrow.") }, minLines = 4,
            modifier = Modifier.fillMaxWidth().testTag("instruction_update_text"))
        Box {
            OutlinedButton(onClick = { chooseStatus = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("update_progress")) {
                Text("Progress: ${status.officerLabel()}")
            }
            DropdownMenu(expanded = chooseStatus, onDismissRequest = { chooseStatus = false }) {
                InstructionWorkflow.progressStates.forEach { item -> DropdownMenuItem(text = { Text(item.officerLabel()) },
                    onClick = { status = item; chooseStatus = false }) }
            }
        }
        if (status == Status.REPORTED_DONE) Text("Reported complete, but not yet verified by you. It stays in Follow up until you mark it done.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        ReminderPicker(followUp, { followUp = it }, title = "Next follow-up",
            supportingText = "When you want to check again. The deadline stays unchanged.")
        if (past) Text("Choose a new follow-up time or remove the previous reminder before saving.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun EditContactSheet(person: Person, busy: Boolean, onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var name by rememberSaveable(person.id) { mutableStateOf(person.name) }
    var rank by rememberSaveable(person.id) { mutableStateOf(person.designation.orEmpty()) }
    var station by rememberSaveable(person.id) { mutableStateOf(person.station.orEmpty()) }
    var phone by rememberSaveable(person.id) { mutableStateOf(person.phone.orEmpty()) }
    WorkspaceEditor("Edit contact", busy, name.isNotBlank(), onDismiss, { onSave(name, rank, station, phone) }) {
        Text("Changes apply only in KaavalanNote, not to your phone’s address book.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("edit_contact_name"))
        OutlinedTextField(rank, { rank = it }, label = { Text("Rank / designation") }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("edit_contact_rank"))
        OutlinedTextField(station, { station = it }, label = { Text("Station / office") }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("edit_contact_station"))
        OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, enabled = !busy,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ContactLinkDialog(contacts: List<Person>, privateMode: Boolean, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Link a contact") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("Search contacts") }, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                if (!privateMode) item { ListItem(headlineContent = { Text("No contact") }, modifier = Modifier.clickable { onPick(null) }) }
                val matches = contacts.filter { listOfNotNull(it.name, it.designation, it.station).joinToString(" ").contains(query.trim(), true) }
                if (matches.isEmpty()) item { Text("No matching contacts", Modifier.padding(vertical = 16.dp)) }
                items(matches, key = { it.id }) { person -> ListItem(headlineContent = { Text(person.name) },
                    supportingContent = { Text(listOfNotNull(person.designation, person.station).joinToString(" · ")) }, modifier = Modifier.clickable { onPick(person.id) }) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
