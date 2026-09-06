package com.kaavalan.note.ui.hierarchy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kaavalan.note.R
import com.kaavalan.note.data.instructions.AudienceRef
import com.kaavalan.note.data.instructions.RosterNode
import com.kaavalan.note.data.instructions.RosterPicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiencePickerSheet(roster: RosterPicker, onPicked: (AudienceRef) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // `rememberCoroutineScope` was previously captured here so the
    // per-branch `scope.launch { sheetState.hide() }` could animate
    // the sheet out. The parent (DispatchComposerSheet) already
    // disposes this composable by setting `pickerOpen = false` once
    // `onPicked` fires, so `sheetState.hide()` is a no-op (the sheet
    // is being torn down, not hidden). Drop the scope + launch calls
    // to keep the call sites readable; only the parent controls the
    // dismiss animation.
    var mode by remember { mutableStateOf<Mode>(Mode.Root) }
    // Drilling into Person/Designation/Station previously had no way
    // back except dismissing the whole sheet (scrim tap or the back
    // gesture), which also threw away the remembered `mode` — so
    // switching from e.g. Designation to Station meant fully closing
    // and reopening the picker. Intercept back the same way
    // AddPersonSheet does (state-conditional BackHandler): while
    // drilled in, back returns to the root chips instead of
    // dismissing; only from Root does back fall through to `onDismiss`.
    BackHandler(enabled = mode != Mode.Root) { mode = Mode.Root }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            // Same discoverability rationale as SettingsSheet's close
            // button (v1.7.1, P1 St1): an in-sheet affordance next to
            // the title, not just an implicit gesture. Here it's a
            // back arrow, visible only once drilled past Root, that
            // returns to the chips without tearing down the sheet.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (mode != Mode.Root) {
                    IconButton(onClick = { mode = Mode.Root }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.hierarchy_audience_picker_back))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.hierarchy_audience_picker_title), style = MaterialTheme.typography.titleLarge)
                    if (mode == Mode.Root) Text(stringResource(R.string.hierarchy_audience_picker_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            when (mode) {
                Mode.Root -> RootChips(roster = roster, onPerson = { mode = Mode.PeopleByDesignation(null, roster.allPeople) }, onDesignation = { mode = Mode.Designations }, onStation = { mode = Mode.Stations }, onAll = { onPicked(AudienceRef.ByAll("all", "Everyone on the roster")) })
                Mode.Designations -> DesignationList(roster, onPick = { d -> onPicked(AudienceRef.ByDesignation(d, "All $d")) })
                Mode.Stations -> StationList(roster, onPick = { s -> onPicked(AudienceRef.ByStation(s, "Everyone at $s")) })
                is Mode.PeopleByDesignation -> {
                    val p = mode as Mode.PeopleByDesignation
                    PersonList(p.people, p.designation ?: stringResource(R.string.hierarchy_audience_by_person), onPick = { per -> onPicked(AudienceRef.ByPerson(per.id, per.name)) })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private sealed interface Mode { data object Root : Mode; data object Designations : Mode; data object Stations : Mode; data class PeopleByDesignation(val designation: String?, val people: List<com.kaavalan.note.data.person.Person>) : Mode }

@Composable private fun RootChips(roster: RosterPicker, onPerson: () -> Unit, onDesignation: () -> Unit, onStation: () -> Unit, onAll: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Counts go in a `Badge` on the leading icon, not in the
            // label text. Screen readers announce "Person 12" instead
            // of "Person open parenthesis twelve close parenthesis".
            AssistChip(onClick = onPerson, label = { Text(stringResource(R.string.hierarchy_audience_by_person)) }, leadingIcon = { Icon(Icons.Default.Person, contentDescription = null); if (roster.totalPeople > 0) Badge { Text(roster.totalPeople.toString()) } })
            AssistChip(onClick = onDesignation, label = { Text(stringResource(R.string.hierarchy_audience_by_designation)) }, leadingIcon = { Icon(Icons.Default.Group, contentDescription = null); if (roster.allDesignations.size > 0) Badge { Text(roster.allDesignations.size.toString()) } })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onStation, label = { Text(stringResource(R.string.hierarchy_audience_by_station)) }, leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null); if (roster.stations.size > 0) Badge { Text(roster.stations.size.toString()) } })
            AssistChip(onClick = onAll, label = { Text(stringResource(R.string.hierarchy_audience_by_all)) }, leadingIcon = { Icon(Icons.Default.Public, contentDescription = null); Badge { Text(roster.totalPeople.toString()) } })
        }
    }
}

@Composable private fun DesignationList(roster: RosterPicker, onPick: (String) -> Unit) {
    // The picker is a ModalBottomSheet; its available height is set
    // by the sheet host. A fixed `height(360.dp)` overflows on small
    // devices (where 360dp is the full screen height) and clips the
    // header on tall ones. `Modifier.weight(1f, fill = false)` lets
    // the LazyColumn size itself from the available height without
    // forcing a fixed bound.
    // v2.1 (a11y regression): onClickLabel matches the InstructionRow /
    // PersonRowSimple convention (HomeHierarchySections.kt) so TalkBack
    // announces the action instead of the generic "double-tap to activate".
    val selectDesignationLabel = stringResource(R.string.a11y_audience_designation_select)
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(roster.allDesignations, key = { it }) { designation ->
            val people = roster.peopleByDesignation(designation)
            Row(modifier = Modifier.fillMaxWidth().clickable(onClickLabel = selectDesignationLabel) { onPick(designation) }.padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp)); Text(designation, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)); Text("${people.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun StationList(roster: RosterPicker, onPick: (String) -> Unit) {
    val selectStationLabel = stringResource(R.string.a11y_audience_station_select)
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(roster.stations, key = { it.station }) { node: RosterNode ->
            Row(modifier = Modifier.fillMaxWidth().clickable(onClickLabel = selectStationLabel) { onPick(node.station) }.padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp)); Text(node.station, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)); Text("${node.totalPeople}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun PersonList(people: List<com.kaavalan.note.data.person.Person>, label: String, onPick: (com.kaavalan.note.data.person.Person) -> Unit) {
    val selectPersonLabel = stringResource(R.string.a11y_audience_person_select)
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(people, key = { it.id }) { person ->
                Row(modifier = Modifier.fillMaxWidth().clickable(onClickLabel = selectPersonLabel) { onPick(person) }.padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(person.name, style = MaterialTheme.typography.bodyLarge)
                        val sub = listOfNotNull(person.designation, person.station).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
