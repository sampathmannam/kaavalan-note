package com.kaavalan.note.features.capture

import android.content.Intent
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaavalan.note.R
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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Tier 0.4: collect the process-wide voice-recording
    // state. When `isRecording == true` the sheet renders an
    // in-app "Stop" button above the primary action; tapping
    // it calls `context.stopService(...)` (the same end
    // state as tapping the notification's Stop action).
    val isVoiceRecording by VoiceCaptureState.isRecording.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
    LaunchedEffect(viewModel) {
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
    LaunchedEffect(viewModel) {
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
    ) {
        CaptureSheetContent(
            state = state,
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
            onTagToggled = viewModel::onTagToggled,
            onAddFreeTag = viewModel::onAddFreeTag,
            onSaveRaw = viewModel::onSaveRaw,
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

@Composable
private fun CaptureSheetContent(
    state: CaptureUiState,
    // Tier 0.4: the in-app voice stop button. Rendered
    // above the Save button when `isVoiceRecording == true`.
    isVoiceRecording: Boolean = false,
    onStopVoice: () -> Unit = {},
    onTextChanged: (String) -> Unit,
    onClose: () -> Unit,
    onAddToCalendarChange: (Boolean) -> Unit = { },
    onTagToggled: (String) -> Unit = { },
    onAddFreeTag: (String) -> Unit = { },
    onSaveRaw: () -> Unit = { },
) {
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetHeader(onClose = onClose)
            CaptureTextField(
                text = state.text,
                isSaving = state.isSaving,
                onTextChanged = onTextChanged,
            )
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
            TagPicker(
                available = state.availableTags,
                selected = state.selectedTagIds,
                onToggle = onTagToggled,
                onAddFree = onAddFreeTag,
            )
            // M1-T6: the "Add to calendar" toggle. Lives next
            // to the primary action so the user sees it before
            // tapping Save. Defaults to off. The intent fires
            // on Save (not on extract) so the user can attach
            // a calendar event to any free-form note.
            AddToCalendarRow(
                addToCalendar = state.addToCalendar,
                onAddToCalendarChange = onAddToCalendarChange,
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
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
            text = stringResource(R.string.capture_sheet_add_to_calendar),
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
        // The button is disabled only while there is no note to
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
