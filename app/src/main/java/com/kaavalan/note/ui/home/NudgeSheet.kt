package com.kaavalan.note.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.nudge.NudgeDraft
import com.kaavalan.note.data.nudge.NudgeDraftGenerator
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * M4-T4: nudge sheet. Edit + Copy + Share. On send, the
 * `nudge_drafts` row is marked SENT.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NudgeSheet(
    instruction: Instruction,
    person: Person?,
    onDismiss: () -> Unit,
    viewModel: NudgeSheetViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    var currentTone by remember { mutableStateOf(com.kaavalan.note.data.nudge.Tone.POLITE) }

    LaunchedEffect(instruction.id) {
        viewModel.ensureDraft(instruction, person, currentTone)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.action_draft_nudge), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.nudge_edit_before_sending),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Copying or opening Share does not confirm delivery. Record any reply in the instruction’s updates.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // v1.1: tone selector. v1.0 had one fixed template;
            // the user wanted to switch to a more urgent tone for
            // a stalling OUTGOING. Three tones mirror the cloud
            // MCP `draft_nudge` tool.
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                com.kaavalan.note.data.nudge.Tone.entries.forEach { tone ->
                    FilterChip(
                        selected = currentTone == tone,
                        onClick = {
                            if (currentTone != tone) {
                                currentTone = tone
                                val cur = draft
                                if (cur != null) {
                                    scope.launch {
                                        viewModel.regenerate(instruction, person, tone)
                                    }
                                }
                            }
                        },
                        label = {
                            Text(
                                when (tone) {
                                    com.kaavalan.note.data.nudge.Tone.POLITE -> stringResource(R.string.nudge_tone_polite)
                                    com.kaavalan.note.data.nudge.Tone.URGENT -> stringResource(R.string.nudge_tone_urgent)
                                    com.kaavalan.note.data.nudge.Tone.CASUAL -> stringResource(R.string.nudge_tone_casual)
                                },
                            )
                        },
                    )
                }
            }
            // Never render a draft from the previously opened instruction while this one loads.
            val current = draft?.takeIf { it.instructionId == instruction.id }
            if (current != null) {
                var text by remember(current.id, current.draftText) { mutableStateOf(current.draftText) }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        scope.launch { viewModel.updateText(current.id, it) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 240.dp),
                    label = { Text(stringResource(R.string.nudge_message_label)) },
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipboardLabel = context.getString(R.string.nudge_clipboard_label)
                            cm.setPrimaryClip(ClipData.newPlainText(clipboardLabel, text))
                            viewModel.recordPreparation(current.id, "COPY")
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.nudge_action_copy)) }
                    Button(
                        onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, text)
                            }
                            val chooserTitle = context.getString(R.string.nudge_share_chooser_title)
                            context.startActivity(
                                android.content.Intent.createChooser(intent, chooserTitle)
                            )
                            // v1.1: "WHATSAPP" is misleading because
                            // the share intent can route to any
                            // app. Use a generic "SHARE" tag.
                            viewModel.recordPreparation(current.id, "SHARE")
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.nudge_action_share)) }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                Text(stringResource(R.string.nudge_generating), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@HiltViewModel
class NudgeSheetViewModel @Inject constructor(
    private val generator: NudgeDraftGenerator,
) : ViewModel() {

    private val _draft = MutableStateFlow<NudgeDraft?>(null)
    val draft: StateFlow<NudgeDraft?> = _draft.asStateFlow()
    private var draftJob: kotlinx.coroutines.Job? = null

    fun ensureDraft(
        instruction: Instruction,
        person: Person?,
        tone: com.kaavalan.note.data.nudge.Tone = com.kaavalan.note.data.nudge.Tone.POLITE,
    ) {
        draftJob?.cancel()
        _draft.value = null
        draftJob = viewModelScope.launch {
            // Reuse the most recent live draft for this instruction
            // if one exists, otherwise generate a new one.
            val existing = generator.observeFor(instruction.id).first()
                .firstOrNull { it.status == "DRAFT" || it.status == "EDITED" }
            _draft.value = existing ?: generator.generate(instruction, person, tone)
        }
    }

    fun regenerate(
        instruction: Instruction,
        person: Person?,
        tone: com.kaavalan.note.data.nudge.Tone,
    ) {
        viewModelScope.launch {
            val cur = _draft.value ?: run {
                ensureDraft(instruction, person, tone); return@launch
            }
            _draft.value = generator.regenerate(cur.id, instruction, person, tone)
        }
    }

    fun updateText(id: String, text: String) {
        viewModelScope.launch { generator.updateText(id, text) }
    }

    fun markSent(id: String, sentVia: String) {
        viewModelScope.launch { generator.markSent(id, sentVia) }
    }

    fun recordPreparation(id: String, action: String) {
        viewModelScope.launch { generator.recordPreparation(id, action) }
    }
}
