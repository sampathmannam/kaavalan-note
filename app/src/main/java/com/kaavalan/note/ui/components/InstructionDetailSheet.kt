package com.kaavalan.note.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kaavalan.note.R
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.Status
import com.kaavalan.note.features.reminder.ReminderPicker
import com.kaavalan.note.features.reminder.formatReminderTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * v1.5.3 (VAULT-010): the instruction detail sheet. Shows the
 * full raw text + a status pill + one of three action buttons
 * depending on the current status.
 *
 *  - OPEN / IN_PROGRESS / WAITING_ON_OTHER / ACK_PENDING:
 *    [Mark done] [Drop]
 *  - DONE / DROPPED: [Reopen]
 *
 * v1.7.0: lifted from TodayScreen so HomeScreen's search
 * results can open the same sheet. The sheet takes pure
 * callbacks for the three actions — the caller wires them
 * to whatever VM owns the instruction (TodayViewModel on
 * Today, SearchResultDetailViewModel on Home). Sharing the
 * sheet keeps the visual contract identical across the two
 * entry points so the user gets the same read/edit affordance
 * whether they got there from the brief or from search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstructionDetailSheet(
    instruction: Instruction,
    onDismiss: () -> Unit,
    onMarkDone: () -> Unit,
    onDrop: () -> Unit,
    onReopen: () -> Unit,
    onReminderChanged: (Long?) -> Unit,
) {
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
                text = instruction.title,
                style = MaterialTheme.typography.headlineSmall,
            )
            StatusPill(instruction.status)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = instruction.rawText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val isClosed = instruction.status == Status.DONE ||
                instruction.status == Status.DROPPED
            if (isClosed && (instruction.dueAtMs != null || instruction.dueAt != null)) {
                Text(
                    text = stringResource(
                        R.string.today_due_at,
                        instruction.dueAtMs?.let { formatReminderTime(it) }
                            ?: formatTimeIso(checkNotNull(instruction.dueAt)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.today_captured_at, formatTimeIso(instruction.capturedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // The action row. Reopen-only for DONE / DROPPED.
            if (!isClosed) {
                ReminderPicker(
                    reminderAtMs = instruction.dueAtMs,
                    onSelected = onReminderChanged,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (isClosed) {
                Button(
                    onClick = onReopen,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_reopen))
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = onMarkDone,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_mark_done))
                    }
                    OutlinedButton(
                        onClick = onDrop,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(stringResource(R.string.action_drop))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * v1.5.3 (VAULT-010): a soft status pill. The colour is
 * surfaceVariant (not red, not amber) — the spec's
 * "no-shame" rule means we never shout at the user about
 * an instruction's state, only label it.
 */
@Composable
private fun StatusPill(status: Status) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = status.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * v1.7.0: visible-for-testing locale formatter. Same shape
 * as the previous TodayScreen.formatTime — `"d MMM, HH:mm"`
 * in the device's local zone. Moved here so the lifted
 * sheet has no dependency on the old private helper.
 */
internal fun formatTimeIso(iso: String): String = try {
    val inst = Instant.parse(iso)
    DateTimeFormatter.ofPattern("d MMM, HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(inst)
} catch (e: Exception) { iso }
