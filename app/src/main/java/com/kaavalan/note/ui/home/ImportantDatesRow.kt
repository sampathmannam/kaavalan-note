package com.kaavalan.note.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaavalan.note.R
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * v2.0.x (BUG FIX, adversarial QA): how long a per-row Delete button
 * stays disabled after the Add-date dialog closes, to absorb a
 * stray tap-through from a rapid double-tap on the dialog's Save
 * button (see the `deleteGuardActive` comment in [ImportantDatesRow]
 * for the full mechanism). Comfortably longer than the ~30ms gap
 * observed in the QA repro, short enough that a deliberate delete
 * tap made any time after actually looking at the revealed list is
 * unaffected.
 */
private const val POST_ADD_DIALOG_DELETE_GUARD_MS = 400L

/**
 * v2.0 Tier 2 (§2.5): a per-person important-dates row, rendered
 * inside [PersonDetailScreen]. Tapping "Add date" opens a dialog
 * with the 3 default labels as chips + a free-form text field, a
 * date picker shortcut (defaults to today), and a "Repeats every
 * year" checkbox.
 */
@Composable
fun ImportantDatesRow(
    viewModel: ImportantDatesViewModel = hiltViewModel(),
) {
    val dates by viewModel.dates.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    // v2.0.x (BUG FIX, adversarial QA): the per-row action used to be
    // labelled "Cancel" (a leftover copy-paste of the generic
    // R.string.cancel resource) but its onClick called
    // `viewModel.delete(d.id)` directly -- a destructive action with a
    // misleading label AND no confirm step, unlike every other
    // destructive flow on this screen (DropDialog,
    // InstructionSensitiveDialog, PersonSensitiveDialog all confirm
    // before acting). Now the row button is correctly labelled
    // "Delete" and only stages the target; the actual delete happens
    // from [DeleteImportantDateDialog]'s confirm button, same
    // stage-then-confirm shape as PersonDetailScreen's DropDialog.
    var deleteTarget by remember { mutableStateOf<com.kaavalan.note.data.local.entities.ImportantDateEntity?>(null) }
    // v2.0.x (BUG FIX, adversarial QA): a rapid double-tap on the
    // Add-date dialog's Save button can have its second tap land on
    // the just-revealed Important Dates list once the dialog closes.
    // Compose tears down the dialog's window on the *next* frame
    // after `showAdd` flips to false -- fast enough (~1 frame) that a
    // second physical tap only ~30ms after the first can already be
    // routed to whatever sits at that same screen position
    // underneath, e.g. an existing row's "Delete" button, opening an
    // unrequested [DeleteImportantDateDialog]. `deleteGuardActive`
    // disables every row's Delete button for a short window right
    // after the Add dialog closes (Save or Cancel/dismiss) so a
    // stray tap-through is swallowed instead of staging a delete --
    // mirrors the `enabled = !state.working` guard-while-transitioning
    // shape used by VaultExportSheet/VaultImportSheet, just keyed on
    // "just closed a dialog" instead of "async work in flight".
    var addDialogClosedNonce by remember { mutableStateOf(0) }
    var deleteGuardActive by remember { mutableStateOf(false) }
    LaunchedEffect(addDialogClosedNonce) {
        if (addDialogClosedNonce == 0) return@LaunchedEffect
        deleteGuardActive = true
        delay(POST_ADD_DIALOG_DELETE_GUARD_MS)
        deleteGuardActive = false
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.important_date_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.important_date_add))
            }
        }
        if (dates.isEmpty()) {
            Text(
                text = stringResource(R.string.important_date_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            dates.forEach { d ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = d.label,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            val date = LocalDate.ofEpochDay(d.dateEpochDay)
                            Text(
                                text = if (d.recurring) "every year - ${date}" else date.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { deleteTarget = d },
                            enabled = !deleteGuardActive,
                        ) {
                            Text(stringResource(R.string.important_date_delete))
                        }
                    }
                }
            }
        }
    }
    if (showAdd) {
        AddImportantDateDialog(
            onAdd = { label, date, recurring ->
                viewModel.add(label, date, recurring)
                showAdd = false
                addDialogClosedNonce++
            },
            onDismiss = {
                showAdd = false
                addDialogClosedNonce++
            },
        )
    }
    val target = deleteTarget
    if (target != null) {
        DeleteImportantDateDialog(
            dateLabel = target.label,
            onConfirm = {
                viewModel.delete(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

/**
 * v2.0.x (BUG FIX, adversarial QA): confirm step for deleting an
 * important date -- mirrors [PersonDetailScreen]'s `DropDialog` /
 * `InstructionSensitiveDialog` shape (stage the target in the caller,
 * confirm here, apply the effect only on confirm).
 */
@Composable
private fun DeleteImportantDateDialog(
    dateLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.important_date_delete_title)) },
        text = { Text(stringResource(R.string.important_date_delete_confirm_message, dateLabel)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.important_date_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun AddImportantDateDialog(
    onAdd: (String, LocalDate, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(ImportantDatesViewModel.DEFAULT_LABELS.first()) }
    var customLabel by remember { mutableStateOf("") }
    var recurring by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var menuOpen by remember { mutableStateOf(false) }
    val resolvedLabel = if (customLabel.isNotBlank()) customLabel else label
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.important_date_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Default-label chips
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ImportantDatesViewModel.DEFAULT_LABELS.forEach { def ->
                        AssistChip(
                            onClick = { label = def; customLabel = "" },
                            label = { Text(def) },
                        )
                    }
                }
                OutlinedTextField(
                    value = customLabel,
                    onValueChange = { customLabel = it },
                    label = { Text(stringResource(R.string.important_date_custom_label_hint)) },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.important_date_label, date),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { menuOpen = true }) {
                        Text(stringResource(R.string.important_date_pick))
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.important_date_today)) },
                            onClick = { date = LocalDate.now(); menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.important_date_tomorrow)) },
                            onClick = { date = LocalDate.now().plusDays(1); menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.important_date_in_a_week)) },
                            onClick = { date = LocalDate.now().plusDays(7); menuOpen = false },
                        )
                    }
                }
                // BUG FIX (found via adversarial QA audit): the row used to
                // rely on the Checkbox itself as the only tap target, so
                // tapping the "Repeats every year" label -- the larger,
                // more natural target -- silently did nothing (confirmed
                // via UI-dump: the Checkbox and Text nodes were
                // non-overlapping clickable regions). Same fix as the
                // onboarding "Add a few sample people" Switch row in
                // OnboardingScreen.kt's GetStartedPage: Modifier.toggleable
                // on the Row makes the whole row (label included) a single
                // tap target, and the Checkbox's own onCheckedChange is set
                // to null so the Row's toggleable is the single source of
                // truth for both the tap handling and the TalkBack
                // "checkbox" role announcement -- keeping both wired would
                // double-toggle on a direct tap of the checkbox glyph.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.toggleable(
                        value = recurring,
                        onValueChange = { recurring = it },
                        role = Role.Checkbox,
                    ),
                ) {
                    Checkbox(checked = recurring, onCheckedChange = null)
                    Text(stringResource(R.string.important_date_recurring))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.important_date_will_save_as, resolvedLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(resolvedLabel, date, recurring) },
                enabled = resolvedLabel.isNotBlank(),
            ) {
                Text(stringResource(R.string.person_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
