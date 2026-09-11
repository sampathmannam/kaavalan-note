package com.kaavalan.note.ui.subdivision

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.data.subdivision.Matter
import com.kaavalan.note.data.subdivision.Station
import com.kaavalan.note.data.subdivision.SubdivisionProfile

/**
 * The shared editor frame for every subdivision form.
 *
 * Content scrolls; Save stays pinned above the keyboard. Back and dismiss only warn when
 * something has actually been typed — an untouched form closes without a dialog. A
 * validation failure keeps every entered value, shows the reason in words and sends focus
 * to the field that needs attention.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SubdivisionEditorFrame(
    title: String,
    busy: Boolean,
    dirty: Boolean,
    error: String?,
    saveLabel: String = "Save",
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val compactTyping = landscape && WindowInsets.isImeVisible
    val requestDismiss = { if (!busy) { if (dirty) confirmDiscard = true else onDismiss() } }
    ModalBottomSheet(
        onDismissRequest = requestDismiss,
        dragHandle = if (landscape) null else ({ BottomSheetDefaults.DragHandle() }),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        com.kaavalan.note.ui.theme.DialogSystemBars()
        Column(Modifier.fillMaxHeight(.94f).imePadding().padding(horizontal = 20.dp)) {
            if (!compactTyping) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp).testTag("subdivision_editor_title"),
                )
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp).testTag("subdivision_editor_error"),
                )
            }
            HorizontalDivider(Modifier.padding(top = if (compactTyping) 0.dp else 12.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = if (compactTyping) 0.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = requestDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Cancel")
                }
                Button(
                    onClick = onSave,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("subdivision_editor_save"),
                ) {
                    Text(if (busy) "Saving…" else saveLabel)
                }
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard your changes?") },
            text = { Text("What you have already saved will not change.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
fun SubdivisionProfileEditor(
    profile: SubdivisionProfile?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(profile?.name.orEmpty()) }
    var district by rememberSaveable { mutableStateOf(profile?.district.orEmpty()) }
    var officer by rememberSaveable { mutableStateOf(profile?.officerName.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val nameFocus = remember { FocusRequester() }
    val dirty = name != profile?.name.orEmpty() || district != profile?.district.orEmpty() ||
        officer != profile?.officerName.orEmpty()
    SubdivisionEditorFrame(
        title = if (profile == null) "Set up your subdivision" else "Edit subdivision",
        busy = busy,
        dirty = dirty,
        error = error,
        onDismiss = onDismiss,
        onSave = {
            if (name.isBlank()) {
                error = "Enter your subdivision name."
                nameFocus.requestFocus()
            } else {
                error = null
                onSave(name, district, officer)
            }
        },
    ) {
        Text(
            "Your private work record. Staff do not need an account.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = null },
            label = { Text("Subdivision name") },
            enabled = !busy,
            isError = error != null,
            modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).testTag("profile_name"),
        )
        OutlinedTextField(
            value = district,
            onValueChange = { district = it },
            label = { Text("District (optional)") },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("profile_district"),
        )
        OutlinedTextField(
            value = officer,
            onValueChange = { officer = it },
            label = { Text("Your display name (optional)") },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("profile_officer"),
        )
    }
}

@Composable
fun StationEditor(
    station: Station?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var name by rememberSaveable(station?.id ?: "new") { mutableStateOf(station?.name.orEmpty()) }
    var kind by rememberSaveable(station?.id ?: "new") { mutableStateOf(station?.kind ?: "Station") }
    var notes by rememberSaveable(station?.id ?: "new") { mutableStateOf(station?.notes.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val nameFocus = remember { FocusRequester() }
    val dirty = name != station?.name.orEmpty() || kind != (station?.kind ?: "Station") ||
        notes != station?.notes.orEmpty()
    SubdivisionEditorFrame(
        title = if (station == null) "Add station / unit" else "Edit station",
        busy = busy,
        dirty = dirty,
        error = error,
        onDismiss = onDismiss,
        onSave = {
            when {
                name.isBlank() -> { error = "Enter a station or unit name."; nameFocus.requestFocus() }
                kind.isBlank() -> error = "Enter a unit type, for example Station."
                else -> { error = null; onSave(name, kind, notes) }
            }
        },
    ) {
        Text(
            "A station or unit is a place work belongs to. Instructions keep the station they were " +
                "recorded at, even after an officer is transferred.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = null },
            label = { Text("Station or unit name") },
            enabled = !busy,
            isError = error != null,
            modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).testTag("station_name"),
        )
        OutlinedTextField(
            value = kind,
            onValueChange = { kind = it; error = null },
            label = { Text("Unit type") },
            supportingText = { Text("For example Station, Outpost, Wing or Cell.") },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("station_kind"),
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes (optional)") },
            minLines = 3,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("station_notes"),
        )
    }
}

@Composable
fun MatterEditor(
    matter: Matter?,
    stations: List<Station>,
    hasLinkedInstructions: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String?, String, String) -> Unit,
) {
    var title by rememberSaveable(matter?.id ?: "new") { mutableStateOf(matter?.title.orEmpty()) }
    var stationId by rememberSaveable(matter?.id ?: "new") { mutableStateOf(matter?.stationId) }
    var reference by rememberSaveable(matter?.id ?: "new") { mutableStateOf(matter?.reference.orEmpty()) }
    var description by rememberSaveable(matter?.id ?: "new") { mutableStateOf(matter?.description.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    val dirty = title != matter?.title.orEmpty() || stationId != matter?.stationId ||
        reference != matter?.reference.orEmpty() || description != matter?.description.orEmpty()
    SubdivisionEditorFrame(
        title = if (matter == null) "Add matter" else "Edit matter",
        busy = busy,
        dirty = dirty,
        error = error,
        onDismiss = onDismiss,
        onSave = {
            if (title.isBlank()) {
                error = "Enter a matter title."
                titleFocus.requestFocus()
            } else {
                error = null
                onSave(title, stationId, reference, description)
            }
        },
    ) {
        Text(
            "Group related instructions and their history.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = title,
            onValueChange = { title = it; error = null },
            label = { Text("Matter title") },
            enabled = !busy,
            isError = error != null,
            modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).testTag("matter_title"),
        )
        Text("Station or unit", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = { choosing = true },
            enabled = !busy && !hasLinkedInstructions,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("matter_station"),
        ) {
            Text(stations.firstOrNull { it.id == stationId }?.name ?: "Subdivision-wide (no station)")
        }
        if (hasLinkedInstructions) {
            Text(
                "This matter already has instructions recorded against its station. Keep its station and " +
                    "move individual instructions explicitly instead.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = reference,
            onValueChange = { reference = it },
            label = { Text("Reference (optional)") },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("matter_reference"),
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Context (optional)") },
            minLines = 4,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("matter_description"),
        )
    }
    if (choosing) {
        StationChooser(
            stations = stations,
            includeSubdivisionWide = true,
            onPick = { stationId = it; choosing = false },
            onDismiss = { choosing = false },
        )
    }
}

@Composable
fun StaffEditor(
    person: Person,
    stations: List<Station>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String?, String, Boolean, Boolean) -> Unit,
) {
    var stationId by rememberSaveable(person.id) { mutableStateOf(person.stationId) }
    var responsibilities by rememberSaveable(person.id) { mutableStateOf(person.responsibilities) }
    var isStaff by rememberSaveable(person.id) { mutableStateOf(person.isStaff) }
    var active by rememberSaveable(person.id) { mutableStateOf(person.staffActive) }
    var choosing by remember { mutableStateOf(false) }
    val dirty = stationId != person.stationId || responsibilities != person.responsibilities ||
        isStaff != person.isStaff || active != person.staffActive
    SubdivisionEditorFrame(
        title = if (person.isStaff) "Edit staff details" else "Add to staff",
        busy = busy,
        dirty = dirty,
        error = null,
        onDismiss = onDismiss,
        onSave = { onSave(stationId, responsibilities, isStaff, active) },
    ) {
        Text(person.name, style = MaterialTheme.typography.titleLarge)
        listOfNotNull(person.designation, person.phone).takeIf { it.isNotEmpty() }?.let {
            Text(
                it.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "This is the same contact as in your Contacts list. Their name and phone number are not changed here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Part of my staff", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = isStaff, onCheckedChange = { isStaff = it }, enabled = !busy, modifier = Modifier.testTag("staff_is_staff"))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Posting currently active", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = active, onCheckedChange = { active = it }, enabled = !busy && isStaff, modifier = Modifier.testTag("staff_active"))
        }
        if (!active) {
            Text(
                "An inactive posting stays searchable and keeps its history. Their existing instructions are " +
                    "not reassigned.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("Station or unit", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = { choosing = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("staff_station"),
        ) {
            Text(stations.firstOrNull { it.id == stationId }?.name ?: "No station assigned")
        }
        OutlinedTextField(
            value = responsibilities,
            onValueChange = { responsibilities = it },
            label = { Text("Responsibilities") },
            supportingText = { Text("What this officer looks after. One line or several.") },
            minLines = 4,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("staff_responsibilities"),
        )
    }
    if (choosing) {
        StationChooser(
            stations = stations,
            includeSubdivisionWide = false,
            onPick = { stationId = it; choosing = false },
            onDismiss = { choosing = false },
        )
    }
}

/**
 * Record a review note. The scope being reviewed is shown, never guessed, and the copy is
 * explicit that saving records a note — it does not complete work or notify anyone.
 */
@Composable
fun ReviewEditor(
    scopeTitle: String,
    openCount: Int,
    readyCount: Int,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var notes by rememberSaveable(scopeTitle) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val notesFocus = remember { FocusRequester() }
    SubdivisionEditorFrame(
        title = "Record review",
        busy = busy,
        dirty = notes.isNotBlank(),
        error = error,
        saveLabel = "Save review",
        onDismiss = onDismiss,
        onSave = {
            if (notes.isBlank()) {
                error = "Write a short note for this review."
                notesFocus.requestFocus()
            } else {
                error = null
                onSave(notes)
            }
        },
    ) {
        Text("Scope", style = MaterialTheme.typography.titleSmall)
        Text(scopeTitle, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("review_scope"))
        Text(
            "$openCount open · $readyCount ready to verify right now. These counts are stored with the note " +
                "as a record of this moment.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it; error = null },
            label = { Text("Review notes") },
            minLines = 6,
            enabled = !busy,
            isError = error != null,
            modifier = Modifier.fillMaxWidth().focusRequester(notesFocus).testTag("review_notes"),
        )
        Text(
            "Saving records your note. It does not complete any instruction and does not send a message to anyone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Change one instruction's recorded station and matter.
 *
 * Shows the current context and previews what it will become before saving, because this
 * is the one action that rewrites where a piece of work is filed.
 */
@Composable
fun WorkContextEditor(
    currentStationName: String?,
    currentMatterTitle: String?,
    stations: List<Station>,
    matters: List<Matter>,
    initialStationId: String?,
    initialMatterId: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String?, String?) -> Unit,
) {
    var stationId by rememberSaveable { mutableStateOf(initialStationId) }
    var matterId by rememberSaveable { mutableStateOf(initialMatterId) }
    var choosingStation by remember { mutableStateOf(false) }
    var choosingMatter by remember { mutableStateOf(false) }
    val nextStation = stations.firstOrNull { it.id == stationId }
    val nextMatter = matters.firstOrNull { it.id == matterId }
    val dirty = stationId != initialStationId || matterId != initialMatterId
    SubdivisionEditorFrame(
        title = "Change work context",
        busy = busy,
        dirty = dirty,
        error = null,
        saveLabel = "Save context",
        onDismiss = onDismiss,
        onSave = { onSave(stationId, matterId) },
    ) {
        Text(
            "This changes where the instruction is filed. It does not change who is responsible for it, " +
                "its deadline or its reminder.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Recorded now", style = MaterialTheme.typography.titleSmall)
        Text(
            "${currentStationName ?: "No station recorded"} · ${currentMatterTitle ?: "No matter"}",
            style = MaterialTheme.typography.bodyLarge,
        )
        HorizontalDivider()
        Text("Station or unit", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = { choosingStation = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("context_station"),
        ) {
            Text(nextStation?.name ?: "No station")
        }
        Text("Matter", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = { choosingMatter = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("context_matter"),
        ) {
            Text(nextMatter?.title ?: "No matter")
        }
        if (nextMatter?.stationId != null && nextMatter.stationId != stationId) {
            Text(
                "\"${nextMatter.title}\" belongs to another station. Choose that station, or pick a " +
                    "subdivision-wide matter.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (dirty) {
            Text("After saving", style = MaterialTheme.typography.titleSmall)
            Text(
                "${nextStation?.name ?: "No station"} · ${nextMatter?.title ?: "No matter"}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("context_preview"),
            )
            Text(
                "This change is added to the instruction's update history.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (choosingStation) {
        StationChooser(
            stations = stations,
            includeSubdivisionWide = true,
            onPick = { stationId = it; choosingStation = false },
            onDismiss = { choosingStation = false },
        )
    }
    if (choosingMatter) {
        MatterChooser(
            matters = matters,
            stationNameOf = { id -> stations.firstOrNull { it.id == id }?.name },
            onPick = { picked ->
                matterId = picked
                // A station-specific matter carries its station with it; a subdivision-wide
                // matter leaves the station alone.
                matters.firstOrNull { it.id == picked }?.stationId?.let { stationId = it }
                choosingMatter = false
            },
            onDismiss = { choosingMatter = false },
        )
    }
}
