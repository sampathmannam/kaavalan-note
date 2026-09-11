package com.kaavalan.note.features.capture

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.data.instructions.Direction
import com.kaavalan.note.ui.workspace.officerLabel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaavalan.note.R
import com.kaavalan.note.features.reminder.ReminderPicker
import com.kaavalan.note.features.tags.TagPicker

/**
 * Modal bottom sheet shown when the user taps the note bar.
 *
 * v1.6.1: the on-device LLM is gone. The sheet is now a
 * single-purpose "type or speak a note, save it" surface. The
 * state machine collapses to:
 *
 *   - open sheet -> text field is empty
 *   - user types (or speaks via [VoiceCaptureService] which
 *     uses the system `SpeechRecognizer`)
 *   - user taps Save -> note persists, sheet closes
 *
 * There is no Extract button, no Confirmation card, no
 * model-download card, and no LLM-unavailable card. A note
 * can be saved without a person; organise it later rather
 * than losing the thought now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureSheet(
    viewModel: CaptureViewModel,
    contacts: List<Person> = emptyList(),
    eventsHandledByHost: Boolean = false,
    // v2.1.2 (P1-#2): skip the partially-expanded state so
    // the sheet has enough vertical room to keep the bottom
    // Save button above the IME. The previous default left
    // the sheet at ~50% height; when the keyboard opened the
    // sheet's content area was smaller than the column, so
    // the fixed-bottom Save button got clipped behind the
    // keyboard regardless of `imePadding()`. Going straight
    // to fully expanded gives the column its full content
    // area, and the `imePadding()` on the outer column then
    // pushes the Save button above the keyboard reliably.
    sheetState: SheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    ),
    onDismiss: () -> Unit,
    // v2.x: the user accepted the audience-mention suggestion and
    // wants to turn this note into a dispatch. Receives the note text
    // typed so far so the composer opens seeded with it.
    onOpenDispatch: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Tier 0.4: collect the process-wide voice-recording
    // state. When `isRecording == true` the sheet renders an
    // in-app "Stop" button above the primary action; tapping
    // it calls `context.stopService(...)` (the same end
    // state as tapping the notification's Stop action).
    val isVoiceRecording by VoiceCaptureState.isRecording.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // v1.8.0 (PROD-READINESS-P0-#4): one-shot info Snackbar
    // host. Distinct from the inline `state.error` Row because
    // the past-date case is a successful save with a caveat,
    // not a failure. The Snackbar overlays the sheet content
    // and auto-dismisses on its own timer; the user does not
    // have to ack it to continue.
    val snackbarHostState = remember { SnackbarHostState() }

    // M1-T6: collect calendar events from the VM and launch
    // them via the Activity context. The Channel is buffered
    // so a config change between save + launch doesn't drop
    // the event.
    LaunchedEffect(viewModel, eventsHandledByHost) {
        if (eventsHandledByHost) return@LaunchedEffect
        viewModel.calendarIntents.collect { event ->
            context.startActivity(CalendarGate.toIntent(event))
        }
    }

    // v1.8.0 (PROD-READINESS-P0-#4): collect one-shot info
    // messages (e.g. "That date is already past — note saved
    // without a calendar reminder.") and surface them via
    // the Snackbar. The Channel is buffered so a config
    // change between save + showSnackbar doesn't drop the
    // message.
    LaunchedEffect(viewModel, eventsHandledByHost) {
        if (eventsHandledByHost) return@LaunchedEffect
        viewModel.infoMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(state.isVisible) {
        if (!state.isVisible) onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.dismissSheet()
            onDismiss()
        },
        sheetState = sheetState,
        // In a short window the handle consumes space needed for the editor.
        dragHandle = if (landscape) null else ({ BottomSheetDefaults.DragHandle() }),
    ) {
        com.kaavalan.note.ui.theme.DialogSystemBars()
        CaptureSheetContent(
            state = state,
            contacts = contacts,
            onDirectionChanged = viewModel::onDirectionChanged,
            onPersonChanged = viewModel::onPersonChanged,
            isVoiceRecording = isVoiceRecording,
            onStopVoice = {
                val svc = Intent(context, VoiceCaptureService::class.java).apply {
                    action = VoiceCaptureService.ACTION_STOP
                }
                context.startService(svc)
            },
            onTextChanged = viewModel::onTextChanged,
            onClose = { viewModel.dismissSheet() },
            onAddToCalendarChange = viewModel::onAddToCalendarChanged,
            onReminderChanged = viewModel::onReminderChanged,
            onTagToggled = viewModel::onTagToggled,
            onAddFreeTag = viewModel::onAddFreeTag,
            onSaveRaw = viewModel::onSaveRaw,
            onOpenDispatch = {
                val text = state.text
                viewModel.dismissSheet()
                onOpenDispatch(text)
            },
            onAcceptPendingContext = viewModel::acceptPendingContext,
            onKeepCurrentContext = viewModel::keepCurrentContext,
            onClearContext = viewModel::clearContext,
        )
    }

    // v1.8.0 (PROD-READINESS-P0-#4): the Snackbar host sits
    // INSIDE the bottom sheet so the message is co-located
    // with the save action it caveats. Using a Box wrapper
    // would clip the Snackbar to the sheet bounds; placing
    // the host as a sibling of the ModalBottomSheet keeps it
    // in the same Composable hierarchy but the ModalBottomSheet
    // already constrains us to the sheet bounds, which is what
    // we want.
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .imePadding()
            .navigationBarsPadding(),
    ) { data ->
        Snackbar(
            snackbarData = data,
            // v1.8.0: no action button. The message is a
            // caveat ("saved, but...") not a question.
            // Auto-dismiss is fine; the user just saved and
            // can move on.
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptureSheetContent(
    state: CaptureUiState,
    contacts: List<Person> = emptyList(),
    onDirectionChanged: (Direction) -> Unit = {},
    onPersonChanged: (String?) -> Unit = {},
    // Tier 0.4: the in-app voice stop button. Rendered
    // above the Save button when `isVoiceRecording == true`.
    isVoiceRecording: Boolean = false,
    onStopVoice: () -> Unit = {},
    onTextChanged: (String) -> Unit,
    onClose: () -> Unit,
    onAddToCalendarChange: (Boolean) -> Unit = { },
    onReminderChanged: (Long?) -> Unit = { },
    onTagToggled: (String) -> Unit = { },
    onAddFreeTag: (String) -> Unit = { },
    onSaveRaw: () -> Unit = { },
    onOpenDispatch: () -> Unit = {},
    onAcceptPendingContext: () -> Unit = {},
    onKeepCurrentContext: () -> Unit = {},
    onClearContext: () -> Unit = {},
) {
    var showContacts by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    val compactTyping = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        WindowInsets.isImeVisible
    // v2.1.2 (P1-#2): the sheet content is split into a
    // scrollable body and a fixed bottom action bar. The
    // previous single-Column-with-verticalScroll design put
    // the Save button at the bottom of the scrollable
    // content; when the IME opened, `verticalScroll`
    // scrolled to keep the Note field visible and pushed
    // the Save button off-screen below the keyboard. A
    // user who tapped where Save used to be (the bottom
    // of the visible sheet) hit the Note field instead.
    // The fix lifts the PrimaryAction out of the
    // scrollable area and pins it above the IME via
    // `imePadding()` on the outer Column (the
    // `ModalBottomSheet` doesn't apply the IME insets to
    // its content by default, so the inset has to be
    // applied here for the bottom action bar to clear the
    // keyboard). The scrollable body keeps the existing
    // `verticalScroll` so overflow (long text + tag picker
    // + calendar toggle) still scrolls
    // inside the sheet.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = if (compactTyping) 4.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Keep the editor at the same composition position as the IME opens:
            // removing/recreating it would lose focus and the current selection.
            // Android Back dismisses the keyboard and restores the full header.
            if (!compactTyping) SheetHeader(onClose = onClose)
            // v2.6.0: the work context this note is being filed into, shown before the
            // field so the officer can see it while typing rather than discovering it
            // after saving.
            state.pendingContext?.let { pending ->
                PendingContextChoice(
                    pendingLabel = pending.label,
                    currentLabel = state.contextLabel,
                    onUseNew = onAcceptPendingContext,
                    onKeepCurrent = onKeepCurrentContext,
                )
            }
            if (state.hasContext && state.contextLabel != null) {
                WorkContextRow(
                    label = state.contextLabel,
                    enabled = !state.isSaving,
                    onClear = onClearContext,
                )
            }
            CaptureTextField(
                text = state.text,
                isSaving = state.isSaving,
                onTextChanged = onTextChanged,
            )
            Text("Who will act on this?", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Direction.SELF, Direction.OUTGOING, Direction.INCOMING).forEach { direction ->
                    FilterChip(selected = state.direction == direction, enabled = !state.isSaving,
                        onClick = { onDirectionChanged(direction) }, label = { Text(direction.officerLabel()) })
                }
            }
            Text(when (state.direction) {
                Direction.SELF -> "A task or decision for you."
                Direction.OUTGOING -> "An instruction you gave. Keep it here to follow up."
                Direction.INCOMING -> "An instruction you received and need to act on."
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showContacts = true }, enabled = !state.isSaving) {
                Text(contacts.firstOrNull { it.id == state.personId }?.let { "Linked to ${it.name} · Change" }
                    ?: "Link a contact (optional)")
            }
            if (state.requiresContact) Text("Link a private contact to keep this note in the private workspace.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // v2.x: the note mentions an audience (@si,
            // @station:Subedari, @all). Offer -- never force -- the
            // dispatch composer. Sits directly under the field so
            // it reads as a response to what was just typed.
            state.dispatchSuggestion?.takeUnless { state.requiresContact }?.let { suggestion ->
                DispatchSuggestionCard(
                    suggestion = suggestion,
                    onOpenDispatch = onOpenDispatch,
                )
            }
            if (state.error != null) {
                // v1.4 (PHONE-FINDING-7): the error is rendered
                // in `onSurfaceVariant` (a neutral grey) -- NEVER
                // `colorScheme.error` (bright red), which would
                // be a spec §1 violation. The icon is
                // `Icons.Outlined.Info` for the standard "I have
                // something to tell you" cue.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = state.error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // M3-T7: tag picker sits below the text field. The
            // user picks from the existing taxonomy or authors a
            // free-form `#tag` on the fly. The state
            // `availableTags` is observed from the VM's collect;
            // `selectedTagIds` is the user's pre-save selection.
            ReminderPicker(
                reminderAtMs = state.reminderAtMs,
                onSelected = onReminderChanged,
            )
            if (state.reminderAtMs != null) {
                AddToCalendarRow(
                    addToCalendar = state.addToCalendar,
                    onAddToCalendarChange = onAddToCalendarChange,
                )
            }
            TextButton(onClick = { showTags = !showTags }) {
                Text(if (showTags) "Hide labels" else if (state.selectedTagIds.isEmpty()) "Add labels (optional)" else "Labels (${state.selectedTagIds.size})")
            }
            if (showTags) TagPicker(
                available = state.availableTags, selected = state.selectedTagIds,
                onToggle = onTagToggled, onAddFree = onAddFreeTag,
            )
        }
        PrimaryAction(
            isSaving = state.isSaving,
            canSaveRaw = state.canSaveRaw,
            isVoiceRecording = isVoiceRecording,
            onStopVoice = onStopVoice,
            onSaveRaw = onSaveRaw,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = if (compactTyping) 4.dp else 12.dp),
        )
    }
    if (showContacts) {
        var query by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showContacts = false },
            title = { Text("Link a work contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose who gave or is handling this instruction.")
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text("Name, rank or station") })
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        item { TextButton(onClick = { onPersonChanged(null); showContacts = false }) { Text("No contact") } }
                        items(contacts.filter { listOfNotNull(it.name, it.designation, it.station).joinToString(" ").contains(query, true) }, key = { it.id }) { person ->
                            TextButton(onClick = { onPersonChanged(person.id); showContacts = false }, modifier = Modifier.fillMaxWidth().testTag("capture_contact_${person.name}")) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(person.name)
                                    Text(listOfNotNull(person.designation, person.station).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (contacts.isEmpty()) Text("Add colleagues in Contacts when you are ready. You can save this note now.")
                }
            },
            confirmButton = { TextButton(onClick = { showContacts = false }) { Text("Back to note") } },
        )
    }
}

/**
 * The work context a capture is filed into, stated plainly and removable in one tap.
 * Never a bare icon: which matter a note lands in is essential context, not decoration.
 */
@Composable
private fun WorkContextRow(label: String, enabled: Boolean, onClear: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("capture_context"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Saving into", style = MaterialTheme.typography.labelLarge)
            Text(label, style = MaterialTheme.typography.bodyLarge)
            TextButton(
                onClick = onClear,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("capture_context_clear"),
            ) {
                Text("Save without a matter")
            }
        }
    }
}

/**
 * A different work context arrived while a draft was already being written. The draft is
 * untouched; this asks which one was meant, naming both, and does nothing until answered.
 */
@Composable
private fun PendingContextChoice(
    pendingLabel: String,
    currentLabel: String?,
    onUseNew: () -> Unit,
    onKeepCurrent: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("capture_pending_context"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("You already have a note in progress", style = MaterialTheme.typography.titleSmall)
            Text(
                "Your text has been kept. Where should it be saved?",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onUseNew,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("capture_context_use_new"),
            ) {
                Text("Save into $pendingLabel")
            }
            androidx.compose.material3.OutlinedButton(
                onClick = onKeepCurrent,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("capture_context_keep"),
            ) {
                Text(currentLabel?.let { "Keep $it" } ?: "Keep this note without a matter")
            }
        }
    }
}

@Composable
private fun SheetHeader(onClose: () -> Unit) {

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.capture_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.capture_sheet_close),
            )
        }
    }
}

@Composable
private fun CaptureTextField(
    text: String,
    isSaving: Boolean,
    onTextChanged: (String) -> Unit,
) {
    val captureNoteTextDesc = stringResource(R.string.a11y_capture_note_text)
    OutlinedTextField(
        value = text,
        onValueChange = onTextChanged,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp, max = 200.dp)
            .testTag("capture_editor")
            .semantics { contentDescription = captureNoteTextDesc },
        label = { Text(stringResource(R.string.capture_sheet_text_label)) },
        placeholder = { Text(stringResource(R.string.capture_sheet_text_placeholder)) },
        shape = RoundedCornerShape(12.dp),
        enabled = !isSaving,
    )
}

@Composable
private fun AddToCalendarRow(
    addToCalendar: Boolean,
    onAddToCalendarChange: (Boolean) -> Unit,
) {
    val addToCalendarDesc = stringResource(R.string.a11y_add_to_calendar)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = addToCalendarDesc },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.reminder_calendar_option),
            style = MaterialTheme.typography.bodyMedium,
        )
        androidx.compose.material3.Switch(
            checked = addToCalendar,
            onCheckedChange = onAddToCalendarChange,
        )
    }
}

@Composable
private fun PrimaryAction(
    isSaving: Boolean,
    canSaveRaw: Boolean,
    // Tier 0.4: the in-app stop-voice affordance. When
    // `isVoiceRecording == true` the action column renders
    // a "Stop voice" button above the Save button.
    isVoiceRecording: Boolean = false,
    onStopVoice: () -> Unit = {},
    onSaveRaw: () -> Unit,
    // v2.1.2 (P1-#2): the caller pins the PrimaryAction
    // above the IME via `imePadding()`. The previous
    // `windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))`
    // lived INSIDE the scrollable content, so when the
    // IME opened and the scroll kept the Note field
    // visible, the Save button was scrolled off-screen
    // below the keyboard. The caller now owns the inset
    // handling because the action bar sits outside the
    // scrollable area.
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Tier 0.4: in-app stop-voice button. Renders
        // above the Save button so the user can reach it
        // without scrolling. The button is a `Button`
        // (not `OutlinedButton`) so it reads as an active
        // affordance; the colour is `primary` /
        // `onPrimary` (no red, per the no-shame spec
        // rule). When the recording is not in progress,
        // the entire row is hidden.
        if (isVoiceRecording) {
            Button(
                onClick = onStopVoice,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.tier0_voice_in_app_stop),
                )
            }
        }
        // v1.6.1: the single Save button. The previous
        // dual-button (Extract + Save as text) is gone --
        // with no LLM there is no extraction step, so a
        // single primary action is the right shape. The
        // button is disabled only while there is no note to
        // save or a save is already in flight. Person context is
        // optional and must not make a brand-new user's capture
        // disappear.
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Button(
                onClick = onSaveRaw,
                enabled = canSaveRaw && !isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.capture_sheet_save))
            }
        }
    }
}

/**
 * v2.x: the audience-mention suggestion. Shown when the typed note
 * contains `@<designation>`, `@station:<name>`, or `@all` -- the
 * decided entry point into the hierarchy dispatch flow, which shipped
 * in v2.1.1 with no way to reach it.
 *
 * Styled as a neutral `surfaceVariant`, never an alert colour,
 * because this is an offer, not a problem. Declining
 * it is silent: the user keeps typing and saves an ordinary note.
 */
@Composable
private fun DispatchSuggestionCard(
    suggestion: DispatchSuggestion,
    onOpenDispatch: () -> Unit,
) {
    val actionLabel = stringResource(R.string.capture_dispatch_suggestion_action)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.capture_dispatch_suggestion_message,
                    suggestion.label,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onOpenDispatch,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = actionLabel },
            ) {
                Text(actionLabel)
            }
        }
    }
}
