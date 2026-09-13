package com.kaavalan.note.features.reminder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kaavalan.note.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A progressive reminder picker: choose a familiar day first, then a time.
 * Exact date/time dialogs remain available, but are no longer the first step.
 */
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
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var dateWindowName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var showPastTimeMessage by rememberSaveable { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }
    val nowMs = System.currentTimeMillis()
    val today = remember(nowMs, zone) { ReminderPresets.localDate(nowMs, zone) }
    val selectedDate = selectedEpochDay?.let(LocalDate::ofEpochDay)
    val dateWindow = dateWindowName?.let(ReminderDateWindow::valueOf)
    val selectReminder: (Long?) -> Unit = { value ->
        if (!deadline && value != null && value <= System.currentTimeMillis()) {
            showPastTimeMessage = true
        } else {
            showPastTimeMessage = false
            onSelected(value)
        }
    }
    val chooseDate: (LocalDate, ReminderDateWindow) -> Unit = { date, window ->
        selectedEpochDay = date.toEpochDay()
        dateWindowName = window.name
        showPastTimeMessage = false
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
                FilterChip(
                    selected = dateWindow == ReminderDateWindow.TODAY,
                    onClick = { chooseDate(today, ReminderDateWindow.TODAY) },
                    label = { Text(stringResource(R.string.reminder_day_today)) },
                    modifier = Modifier.testTag("reminder_day_today"),
                )
                FilterChip(
                    selected = dateWindow == ReminderDateWindow.TOMORROW,
                    onClick = { chooseDate(today.plusDays(1), ReminderDateWindow.TOMORROW) },
                    label = { Text(stringResource(R.string.reminder_day_tomorrow)) },
                    modifier = Modifier.testTag("reminder_day_tomorrow"),
                )
                FilterChip(
                    selected = dateWindow == ReminderDateWindow.THIS_WEEK,
                    onClick = {
                        dateWindowName = ReminderDateWindow.THIS_WEEK.name
                        selectedEpochDay = null
                    },
                    label = { Text(stringResource(R.string.reminder_day_this_week)) },
                    modifier = Modifier.testTag("reminder_day_this_week"),
                )
                FilterChip(
                    selected = dateWindow == ReminderDateWindow.THIS_MONTH,
                    onClick = {
                        dateWindowName = ReminderDateWindow.THIS_MONTH.name
                        showDatePicker = true
                    },
                    label = { Text(stringResource(R.string.reminder_day_this_month)) },
                    modifier = Modifier.testTag("reminder_day_this_month"),
                )
            }
            AssistChip(
                onClick = {
                    dateWindowName = ReminderDateWindow.CUSTOM.name
                    showDatePicker = true
                },
                label = { Text(stringResource(R.string.reminder_preset_custom)) },
            )
        }

        if (dateWindow == ReminderDateWindow.THIS_WEEK && selectedDate == null) {
            Text(
                text = stringResource(R.string.reminder_choose_day),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ReminderPresets.thisWeekDates(nowMs, zone).forEach { date ->
                    AssistChip(
                        onClick = { chooseDate(date, ReminderDateWindow.THIS_WEEK) },
                        label = { Text(reminderDayLabel(date, today)) },
                    )
                }
            }
        }

        if (selectedDate != null) {
            Text(
                text = stringResource(
                    R.string.reminder_choose_time,
                    reminderDayLabel(selectedDate, today),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!deadline && selectedDate == today) {
                    AssistChip(
                        onClick = { selectReminder(ReminderPresets.inOneHour(System.currentTimeMillis())) },
                        label = { Text(stringResource(R.string.reminder_preset_one_hour)) },
                    )
                }
                ReminderPresets.suggestedTimes(selectedDate, System.currentTimeMillis(), zone).forEach { time ->
                    AssistChip(
                        onClick = { selectReminder(ReminderPresets.atTime(selectedDate, time, zone)) },
                        label = { Text(formatReminderClock(time)) },
                    )
                }
                AssistChip(
                    onClick = { showTimePicker = true },
                    label = { Text(stringResource(R.string.reminder_pick_time)) },
                )
            }
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
        val restrictToMonth = dateWindow == ReminderDateWindow.THIS_MONTH
        val firstAllowed = today
        val lastAllowed = if (restrictToMonth) today.withDayOfMonth(today.lengthOfMonth()) else LocalDate.MAX
        val selectableDates = remember(firstAllowed, lastAllowed) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !date.isBefore(firstAllowed) && !date.isAfter(lastAllowed)
                }

                override fun isSelectableYear(year: Int): Boolean =
                    year in firstAllowed.year..lastAllowed.year
            }
        }
        val requestedInitialDate = selectedDate
            ?: reminderAtMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            ?: if (restrictToMonth) today.plusDays(1).coerceAtMost(lastAllowed) else today.plusDays(1)
        val initialDate = if (restrictToMonth) {
            requestedInitialDate.coerceIn(firstAllowed, lastAllowed)
        } else {
            requestedInitialDate.coerceAtLeast(firstAllowed)
        }
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = ReminderPresets.utcDateMillis(initialDate),
            selectableDates = selectableDates,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let { selectedMillis ->
                            val date = Instant.ofEpochMilli(selectedMillis).atZone(ZoneOffset.UTC).toLocalDate()
                            chooseDate(date, dateWindow ?: ReminderDateWindow.CUSTOM)
                        }
                        showDatePicker = false
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

    if (showTimePicker && selectedDate != null) {
        val initialTime = reminderAtMs
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
            ?: ReminderPresets.defaultTime(selectedDate, System.currentTimeMillis(), zone)
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
                                selectReminder(
                                    ReminderPresets.atTime(
                                        selectedDate,
                                        LocalTime.of(timeState.hour, timeState.minute),
                                        zone,
                                    ),
                                )
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

internal enum class ReminderDateWindow { TODAY, TOMORROW, THIS_WEEK, THIS_MONTH, CUSTOM }

internal object ReminderPresets {
    private val clockTimes = listOf(LocalTime.of(9, 0), LocalTime.of(13, 0), LocalTime.of(18, 0), LocalTime.of(20, 0))

    fun localDate(nowMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

    fun inOneHour(nowMs: Long): Long = nowMs + 60L * 60L * 1000L

    fun tomorrowMorning(nowMs: Long, zone: ZoneId): Long =
        atTime(localDate(nowMs, zone).plusDays(1), LocalTime.of(9, 0), zone)

    fun nextWeekMorning(nowMs: Long, zone: ZoneId): Long =
        atTime(localDate(nowMs, zone).plusWeeks(1), LocalTime.of(9, 0), zone)

    fun thisWeekDates(nowMs: Long, zone: ZoneId): List<LocalDate> {
        val today = localDate(nowMs, zone)
        val daysToSunday = (DayOfWeek.SUNDAY.value - today.dayOfWeek.value + 7) % 7
        return (0..daysToSunday).map { today.plusDays(it.toLong()) }
    }

    fun suggestedTimes(date: LocalDate, nowMs: Long, zone: ZoneId): List<LocalTime> =
        clockTimes.filter { atTime(date, it, zone) > nowMs }

    fun defaultTime(date: LocalDate, nowMs: Long, zone: ZoneId): LocalTime =
        suggestedTimes(date, nowMs, zone).firstOrNull()
            ?: Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime().plusHours(1).withMinute(0)

    fun atTime(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()

    fun utcDateMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

internal fun formatReminderClock(time: LocalTime): String =
    DateTimeFormatter.ofPattern("HH:mm").format(time)

internal fun formatReminderDay(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM"))
}

@Composable
private fun reminderDayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.reminder_day_today)
    today.plusDays(1) -> stringResource(R.string.reminder_day_tomorrow)
    else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM"))
}

internal fun formatReminderTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm")
        .withZone(zone)
        .format(Instant.ofEpochMilli(epochMs))
