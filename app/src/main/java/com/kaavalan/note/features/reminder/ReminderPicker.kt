package com.kaavalan.note.features.reminder

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kaavalan.note.R
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Inline quick choices with a Material date/time picker for custom reminders. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReminderPicker(
    reminderAtMs: Long?,
    onSelected: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    supportingText: String? = null,
    deadline: Boolean = false,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDateMillis by remember { mutableStateOf<Long?>(null) }
    var showPastTimeMessage by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }
    val selectReminder: (Long?) -> Unit = { value ->
        if (!deadline && value != null && value <= System.currentTimeMillis()) {
            showPastTimeMessage = true
        } else {
            showPastTimeMessage = false
            onSelected(value)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title ?: stringResource(R.string.reminder_picker_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = supportingText ?: stringResource(R.string.reminder_picker_supporting_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!deadline) {
            AssistChip(
                onClick = {
                    selectReminder(ReminderPresets.inOneHour(System.currentTimeMillis()))
                },
                label = { Text(stringResource(R.string.reminder_preset_one_hour)) },
            )
            AssistChip(
                onClick = {
                    selectReminder(
                        ReminderPresets.tomorrowMorning(
                            nowMs = System.currentTimeMillis(),
                            zone = zone,
                        ),
                    )
                },
                label = { Text(stringResource(R.string.reminder_preset_tomorrow)) },
            )
            AssistChip(
                onClick = {
                    selectReminder(
                        ReminderPresets.nextWeekMorning(
                            nowMs = System.currentTimeMillis(),
                            zone = zone,
                        ),
                    )
                },
                label = { Text(stringResource(R.string.reminder_preset_next_week)) },
            )
            }
            AssistChip(
                onClick = { showDatePicker = true },
                label = { Text(stringResource(R.string.reminder_preset_custom)) },
            )
        }

        if (reminderAtMs != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.reminder_picker_scheduled,
                        formatReminderTime(reminderAtMs, zone),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { selectReminder(null) }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = if (deadline) "Remove deadline" else stringResource(R.string.reminder_picker_clear),
                    )
                }
            }
        }
        if (showPastTimeMessage) {
            Text(
                text = stringResource(R.string.reminder_past_time),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = reminderAtMs ?: ReminderPresets.tomorrowMorning(
                nowMs = System.currentTimeMillis(),
                zone = zone,
            ),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDateMillis = dateState.selectedDateMillis
                        showDatePicker = false
                        showTimePicker = pendingDateMillis != null
                    },
                ) {
                    Text(stringResource(R.string.date_picker_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.date_picker_cancel))
                }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showTimePicker) {
        val initialTime = reminderAtMs
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
            ?: LocalTime.of(9, 0)
        val timeState = rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute,
            is24Hour = true,
        )
        Dialog(onDismissRequest = { showTimePicker = false }) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TimePicker(state = timeState)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showTimePicker = false }) {
                            Text(stringResource(R.string.date_picker_cancel))
                        }
                        TextButton(
                            onClick = {
                                pendingDateMillis?.let { selectedDateMillis ->
                                    val date = Instant.ofEpochMilli(selectedDateMillis)
                                        .atZone(java.time.ZoneOffset.UTC)
                                        .toLocalDate()
                                    selectReminder(
                                        LocalDateTime.of(
                                            date,
                                            LocalTime.of(timeState.hour, timeState.minute),
                                        ).atZone(zone).toInstant().toEpochMilli(),
                                    )
                                }
                                showTimePicker = false
                            },
                        ) {
                            Text(stringResource(R.string.date_picker_ok))
                        }
                    }
                }
            }
        }
    }
}

internal object ReminderPresets {
    fun inOneHour(nowMs: Long): Long = nowMs + 60L * 60L * 1000L

    fun tomorrowMorning(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs)
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atTime(9, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    fun nextWeekMorning(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs)
            .atZone(zone)
            .toLocalDate()
            .plusWeeks(1)
            .atTime(9, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}

internal fun formatReminderTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm")
        .withZone(zone)
        .format(Instant.ofEpochMilli(epochMs))
