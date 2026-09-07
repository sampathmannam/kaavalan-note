package com.kaavalan.note.features.widget

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaavalan.note.R
import com.kaavalan.note.data.captures.CaptureMode
import com.kaavalan.note.data.captures.CaptureRepository
import com.kaavalan.note.features.theme.appDarkTheme
import com.kaavalan.note.ui.theme.KaavalanNoteTheme
import com.kaavalan.note.ui.util.SafeError
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * v1.9.10: the entry-point activity for the **Quick Note** home-screen
 * widget.
 *
 * The widget (see [KaavalanQuickNoteWidget]) is a single "+ Quick note"
 * button. The button does NOT open the main app — the user's complaint
 * was "instead of opening the app". Instead, this fullscreen Compose
 * activity launches with a single [androidx.compose.material3.OutlinedTextField]
 * already focused, so the keyboard appears immediately. The user types,
 * hits the on-screen "Save" button (or the IME's Done key), and is
 * returned to the home screen in **2 taps + typing** — versus the
 * 3+ taps (open app → wait for Home → tap the note bar → wait for
 * sheet → type → save) the main app requires.
 *
 * **What "save" does.** [viewModel.save] writes a `Capture` row via
 * [CaptureRepository.create] with [CaptureMode.TEXT]. The capture
 * shows up immediately in the app's Home → Recent Captures feed; the
 * existing v1.9.9 atomic-create guarantees the row + sync-queue
 * entry are committed together (or both rolled back on process
 * death). No Supabase call — app's offline-first threat model means
 * the capture lives in the local SQLCipher DB until the next sync
 * window.
 *
 * **Privacy.** The activity follows the app's Theme setting, like
 * every other screen (v2.2.1 — it used to follow the OS instead).
 * The text field is the only data shown on screen; nothing is logged to
 * logcat. The previous note's content is not shown — the field
 * starts empty every time, so a quick second note doesn't leak the
 * first one to whoever glances at the screen between two saves.
 *
 * **Why a separate activity, not a Compose dialog inside MainActivity.**
 * Dialogs need a host Activity in the started state. The widget click
 * goes via `actionStartActivity<QuickNoteActivity>()` which starts a
 * fresh task on top of the launcher; using a dialog in the main app
 * would require the app to already be in memory AND in the
 * foreground, which contradicts the "instead of opening the app"
 * requirement.
 *
 * **State contract.** [SaveState] is a tiny sealed class so the
 * UI can render the in-flight vs. error transitions without
 * leaking exceptions to the user.
 */
@AndroidEntryPoint
class QuickNoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // v2.2.1: `darkTheme` was left off here, so this screen
            // took the parameter's `isSystemInDarkTheme()` default and
            // followed the OS while the rest of the app followed the
            // user's Theme setting. See [appDarkTheme].
            KaavalanNoteTheme(darkTheme = appDarkTheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    QuickNoteScreen(
                        onCancel = { finish() },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickNoteScreen(
    onCancel: () -> Unit,
    viewModel: QuickNoteViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val state by viewModel.state.collectAsState()

    // Auto-focus the text field + raise the keyboard so the
    // first keystroke lands in the field. Without this, the
    // user has to tap the field before typing — an extra
    // tap that breaks the "quick" promise.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    // When the save completes, update the widget and close the
    // activity. `LaunchedEffect(state)` re-runs only when state
    // changes, so the side effects fire exactly once per save.
    LaunchedEffect(state) {
        when (val s = state) {
            is SaveState.Saved -> {
                refreshQuickNoteWidget(context)
                Toast.makeText(
                    context,
                    context.getString(R.string.quick_note_saved_toast),
                    Toast.LENGTH_SHORT,
                ).show()
                onCancel()
            }
            else -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.quick_note_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.quick_note_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(MAX_NOTE_LENGTH) },
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .focusRequester(focusRequester),
            placeholder = { Text(stringResource(R.string.quick_note_placeholder)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (text.isNotBlank() && state !is SaveState.Saving) {
                        scope.launch { viewModel.save(text) }
                    }
                },
            ),
            supportingText = {
                Text(
                    text = "${text.length} / $MAX_NOTE_LENGTH",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            // Right-aligned action row: Cancel (text button) +
            // Save (filled button). The Save button is disabled
            // when the field is empty or a save is in flight.
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onCancel,
                    enabled = state !is SaveState.Saving,
                ) {
                    Text(stringResource(R.string.quick_note_cancel))
                }
                Button(
                    onClick = {
                        if (state !is SaveState.Saving) {
                            scope.launch { viewModel.save(text) }
                        }
                    },
                    enabled = text.isNotBlank() && state !is SaveState.Saving,
                ) {
                    Text(stringResource(R.string.quick_note_save))
                }
            }
        }
        if (state is SaveState.Error) {
            Text(
                text = (state as SaveState.Error).message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * ViewModel for [QuickNoteActivity]. The save is a fire-and-forget
 * call to [CaptureRepository.create]; we hold the latest
 * [SaveState] in a [StateFlow] so the activity UI can render
 * the saving / saved / error transitions.
 */
@HiltViewModel
class QuickNoteViewModel @Inject constructor(
    private val captureRepository: CaptureRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<SaveState>(SaveState.Idle)
    val state: StateFlow<SaveState> = _state.asStateFlow()

    /**
     * Save the given [rawText] as a TEXT-mode [com.kaavalan.note.data.captures.Capture].
     * Returns immediately if the text is blank; the activity UI
     * also disables the Save button in that case (defense in depth).
     *
     * On success, [SaveState.Saved] is emitted. On failure, the
     * exception is captured into [SaveState.Error] with a
     * user-facing message that does NOT leak the underlying
     * stack trace (BEAU-NEW-01 invariant).
     */
    suspend fun save(rawText: String) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return
        _state.value = SaveState.Saving
        try {
            captureRepository.create(trimmed, CaptureMode.TEXT)
            _state.value = SaveState.Saved
        } catch (e: Throwable) {
            // v1.9.10: the first implementation surfaced
            // `e.message` directly, which leaked internal
            // details (URLs, error codes, stack-trace text)
            // — see [QuickNoteViewModelTest.failed save
            // transitions to Error with safe user-facing
            // message]. SafeError.forUser maps known
            // exceptions to user-facing messages and falls
            // back to the default for unknown ones. The
            // network-failure branch is what most captures
            // will hit when offline.
            _state.value = SaveState.Error(
                SafeError.forUser(e, "Could not save note."),
            )
        }
    }
}

sealed class SaveState {
    data object Idle : SaveState()
    data object Saving : SaveState()
    data object Saved : SaveState()
    data class Error(val message: String) : SaveState()
}

/**
 * Convenience for the Compose `stringResource(...)` call site.
 * Glance isn't involved here — the activity uses stock Compose,
 * so the standard `androidx.compose.ui.res.stringResource` is in
 * scope. We re-import the symbol below to keep the file
 * self-contained.
 */
@Composable
private fun stringResource(resId: Int): String =
    androidx.compose.ui.res.stringResource(resId)

/**
 * Refresh the [KaavalanQuickNoteWidget] after a save so the next
 * `provideGlance` invocation reflects the latest capture (e.g.
 * a "Last saved: just now" subtitle, when the widget grows
 * that field in v1.9.11). For v1.9.10 the widget is stateless
 * and the refresh is a no-op for the user-visible content, but
 * we keep the call so adding a state field in v1.9.11 is a
 * pure additive change.
 *
 * **Why a coroutine block.** [GlanceAppWidgetManager.getGlanceIds]
 * and the per-id [KaavalanQuickNoteWidget.update] are both suspend
 * functions. We call this from [LaunchedEffect] which is a
 * coroutine scope, so we don't need to launch our own.
 */
private suspend fun refreshQuickNoteWidget(context: android.content.Context) {
    val mgr = GlanceAppWidgetManager(context)
    val glanceIds = mgr.getGlanceIds(KaavalanQuickNoteWidget::class.java)
    if (glanceIds.isEmpty()) return
    glanceIds.forEach { id ->
        runCatching { KaavalanQuickNoteWidget().update(context, id) }
    }
}

private const val MAX_NOTE_LENGTH: Int = 2_000
