package com.kaavalan.note.ui.hierarchy

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kaavalan.note.R
import com.kaavalan.note.data.person.ContactSyncService
import com.kaavalan.note.data.person.ContactSyncService.LoadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Search the complete accessible directory, select several people, then import once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactPickerSheet(
    contactSyncService: ContactSyncService,
    onPicked: (contacts: List<ContactSyncService.ContactCandidate>) -> Unit,
    onDismiss: () -> Unit,
    isSaving: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var reload by remember { mutableIntStateOf(0) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedKeys by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        reload++
    }
    // Includes returning from permission settings; never retain a stale granted/denied snapshot.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) reload++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var result by remember(contactSyncService) { mutableStateOf<LoadResult?>(null) }
    LaunchedEffect(contactSyncService, reload) {
        result = null
        result = contactSyncService.fetchContactCandidates()
    }
    val loaded = result as? LoadResult.Loaded
    val allContacts = loaded?.contacts.orEmpty()
    // Searching large directories stays off the UI thread; stale searches are cancelled.
    var filtered by remember { mutableStateOf<List<ContactSyncService.ContactCandidate>?>(null) }
    LaunchedEffect(loaded, query) {
        filtered = null
        filtered = withContext(Dispatchers.Default) { loaded?.contacts?.filter { it.matches(query) } }
    }
    val selectedContacts = allContacts.filter { it.key in selectedKeys }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .imePadding()
                .testTag("phone_contacts_list"),
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.hierarchy_contact_sync_picker_title),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.contact_close)) }
                    }
                    Text(
                        stringResource(R.string.contact_import_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (result) {
                    null -> item { ContactLoading() }
                    LoadResult.PermissionRequired -> item {
                        Text(stringResource(if (permissionDenied) R.string.contact_permission_denied else R.string.hierarchy_contact_sync_permission_rationale))
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.hierarchy_contact_sync_grant))
                        }
                        if (permissionDenied) {
                            TextButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.contact_open_settings))
                            }
                        }
                    }
                    LoadResult.Failed -> item {
                        Text(stringResource(R.string.contact_load_failed))
                        Button(onClick = { reload++ }) { Text(stringResource(R.string.contact_retry)) }
                    }
                    is LoadResult.Loaded -> {
                        item {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                label = { Text(stringResource(R.string.contact_search)) },
                                modifier = Modifier.fillMaxWidth().testTag("phone_contacts_search"),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.contact_selected_count, selectedContacts.size),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                if (selectedKeys.isNotEmpty()) {
                                    TextButton(onClick = { selectedKeys = emptySet() }, enabled = !isSaving) {
                                        Text(stringResource(R.string.contact_clear_selection))
                                    }
                                }
                            }
                            if (!filtered.isNullOrEmpty()) {
                                TextButton(
                                    onClick = {
                                        val visible = filtered.orEmpty()
                                        val visibleSourceIds = visible.map { it.sourceContactId }.toSet()
                                        val firstForEachContact = visible.distinctBy { it.sourceContactId }
                                        selectedKeys = selectedKeys
                                            .filterTo(mutableSetOf()) { selectedKey ->
                                                allContacts.firstOrNull { it.key == selectedKey }
                                                    ?.sourceContactId !in visibleSourceIds
                                            }
                                            .plus(firstForEachContact.map { it.key })
                                    },
                                    enabled = !isSaving,
                                ) {
                                    Text(stringResource(R.string.contact_select_all_results))
                                }
                            }
                        }
                        when {
                            allContacts.isEmpty() -> item { Text(stringResource(R.string.hierarchy_contact_sync_no_results)) }
                            filtered == null -> item { ContactLoading() }
                            filtered.orEmpty().isEmpty() -> item { Text(stringResource(R.string.contact_search_empty)) }
                            else -> items(filtered.orEmpty(), key = { it.key }) { contact ->
                                val name = contact.displayName.ifBlank { stringResource(R.string.contact_unnamed) }
                                val checked = contact.key in selectedKeys
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 64.dp)
                                        .toggleable(
                                            value = checked,
                                            enabled = !isSaving,
                                            role = Role.Checkbox,
                                            onValueChange = { shouldSelect ->
                                                val sameContactKeys = allContacts
                                                    .filter { it.sourceContactId == contact.sourceContactId }
                                                    .mapTo(mutableSetOf()) { it.key }
                                                selectedKeys = if (shouldSelect) {
                                                    (selectedKeys - sameContactKeys) + contact.key
                                                } else {
                                                    selectedKeys - contact.key
                                                }
                                            },
                                        )
                                        .padding(vertical = 6.dp)
                                        .testTag("phone_contact_${contact.key}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = null, enabled = !isSaving)
                                    Column(Modifier.weight(1f)) {
                                        Text(name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            contact.phone.ifBlank { stringResource(R.string.contact_no_phone) },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }

            if (result is LoadResult.Loaded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Button(
                        onClick = { onPicked(selectedContacts) },
                        enabled = selectedContacts.isNotEmpty() && !isSaving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("import_selected_contacts"),
                    ) {
                        Text(
                            when {
                                isSaving -> stringResource(R.string.contact_importing)
                                selectedContacts.size == 1 -> stringResource(R.string.contact_import_one)
                                else -> stringResource(R.string.contact_import_selected, selectedContacts.size)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactLoading() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.contact_loading))
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
