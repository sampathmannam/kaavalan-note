package com.kaavalan.note.ui.hierarchy

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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

/** Search the complete accessible directory; a tap imports one selected contact/number. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactPickerSheet(
    contactSyncService: ContactSyncService,
    onPicked: (displayName: String, phone: String) -> Unit,
    onDismiss: () -> Unit,
    isSaving: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var reload by remember { mutableIntStateOf(0) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
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
    // Searching large directories stays off the UI thread; stale searches are cancelled.
    var filtered by remember { mutableStateOf<List<ContactSyncService.ContactCandidate>?>(null) }
    LaunchedEffect(loaded, query) {
        filtered = null
        filtered = withContext(Dispatchers.Default) { loaded?.contacts?.filter { it.matches(query) } }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).imePadding().testTag("phone_contacts_list"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.hierarchy_contact_sync_picker_title),
                        style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.contact_close)) }
                }
                Text(stringResource(R.string.contact_import_hint), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (result) {
                null -> item { ContactLoading() }
                LoadResult.PermissionRequired -> item {
                    Text(stringResource(if (permissionDenied) R.string.contact_permission_denied else R.string.hierarchy_contact_sync_permission_rationale))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.hierarchy_contact_sync_grant))
                    }
                    if (permissionDenied) {
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.contact_open_settings)) }
                    }
                }
                LoadResult.Failed -> item {
                    Text(stringResource(R.string.contact_load_failed))
                    Button(onClick = { reload++ }) { Text(stringResource(R.string.contact_retry)) }
                }
                is LoadResult.Loaded -> {
                    item {
                        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                            label = { Text(stringResource(R.string.contact_search)) },
                            modifier = Modifier.fillMaxWidth().testTag("phone_contacts_search"))
                        TextButton(onClick = { reload++ }) { Text(stringResource(R.string.contact_refresh)) }
                    }
                    when {
                        loaded?.contacts?.isEmpty() == true -> item { Text(stringResource(R.string.hierarchy_contact_sync_no_results)) }
                        filtered == null -> item { ContactLoading() }
                        filtered.orEmpty().isEmpty() -> item { Text(stringResource(R.string.contact_search_empty)) }
                        else -> items(filtered.orEmpty(), key = { it.key }) { contact ->
                            val name = contact.displayName.ifBlank { stringResource(R.string.contact_unnamed) }
                            Column(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .clickable(enabled = !isSaving) { onPicked(name, contact.phone) }
                                .padding(vertical = 8.dp)) {
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                Text(contact.phone.ifBlank { stringResource(R.string.contact_no_phone) },
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
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
