package com.kaavalan.note.ui.workspace

import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.SnackbarDuration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kaavalan.note.MainActivity
import com.kaavalan.note.R
import com.kaavalan.note.RootViewModel
import com.kaavalan.note.Routes
import com.kaavalan.note.data.undo.UndoController
import com.kaavalan.note.features.capture.CaptureHost
import com.kaavalan.note.features.capture.CaptureViewModel
import com.kaavalan.note.features.capture.NoteBar
import com.kaavalan.note.features.vault.VaultExportSheet
import com.kaavalan.note.features.vault.VaultImportSheet
import com.kaavalan.note.ui.privacy.RecoveryPhraseScreen
import com.kaavalan.note.ui.privacy.ThreatModelScreen
import com.kaavalan.note.ui.settings.SettingsSheet
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun OfficerAppRoot(
    rootViewModel: RootViewModel,
    undoController: UndoController,
    onRequestNotificationsPermission: () -> Unit,
) {
    val navController = rememberNavController()
    var showSettings by remember { mutableStateOf(false) }
    var showVaultExport by remember { mutableStateOf(false) }
    var showVaultImport by remember { mutableStateOf(false) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.TODAY
    val workspace: WorkspaceViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val capture: CaptureViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val workspaceState by workspace.state.collectAsStateWithLifecycle()
    val workspaceBusy by workspace.busy.collectAsStateWithLifecycle()
    var selectedInstructionId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddContact by rememberSaveable { mutableStateOf(false) }
    var editContactId by rememberSaveable { mutableStateOf<String?>(null) }
    var shareInstructionId by rememberSaveable { mutableStateOf<String?>(null) }
    var privacyInstructionId by rememberSaveable { mutableStateOf<String?>(null) }
    var showImportContact by rememberSaveable { mutableStateOf(false) }
    var today by remember { mutableStateOf(java.time.LocalDate.now()) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        today = java.time.LocalDate.now()
    }
    LaunchedEffect(Unit) {
        while (true) {
            today = java.time.LocalDate.now()
            kotlinx.coroutines.delay(30_000)
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val lastUndo by undoController.last.collectAsStateWithLifecycle()
    val pendingReminderInstructionId by rootViewModel.pendingReminderInstructionId
        .collectAsStateWithLifecycle()
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(workspace) { workspace.messages.collect { snackbarHostState.showSnackbar(it) } }
    LaunchedEffect(workspace) {
        workspace.completed.collectLatest { undo ->
            snackbarHostState.currentSnackbarData?.dismiss()
            if (snackbarHostState.showSnackbar("Marked done", actionLabel = "Undo", withDismissAction = true,
                duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) workspace.undoComplete(undo)
        }
    }
    LaunchedEffect(pendingReminderInstructionId, workspaceState) {
        val id = pendingReminderInstructionId ?: return@LaunchedEffect
        if (!workspaceState.loading && workspaceState.error == null) {
            if (workspaceState.instructions.any { it.id == id }) selectedInstructionId = id
            else snackbarHostState.showSnackbar("This instruction is not available in this workspace.")
            rootViewModel.consumeReminderInstruction()
        }
    }

    // v2.0: a single SnackbarHostState listens to the UndoController
    // flow. When a new action arrives, the snackbar shows "Action
    // undone" + an "Undo" button. 5 s auto-dismissal maps to
    // SnackbarDuration.Short. On undo, the controller's
    // `undoLast()` re-inserts the row.
    //
    // v1.9.6 (drive-verify polish #6): the message must show
    // the human-readable name (`action.displayName`), NOT a
    // UUID fragment (`action.id.take(6)`). The v1.9.5
    // implementation read the first 6 chars of the contact's
    // UUID, so the snackbar read "Mark recent 96ldae" instead
    // of "Mark recent B. Ramesh Naidu". Every `UndoableAction`
    // variant now declares its own `displayName` (person name,
    // instruction title, capture preview, or person name for
    // MarkPersonRecent).
    LaunchedEffect(lastUndo) {
        val action = lastUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "${action.label} ${action.displayName}",
            actionLabel = undoLabel,
            withDismissAction = true,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> {
                scope.launch { undoController.undoLast() }
            }
            SnackbarResult.Dismissed -> {
                undoController.clear()
            }
        }
    }

    LaunchedEffect(pendingReminderInstructionId) {
        if (pendingReminderInstructionId != null) {
            navController.navigate(Routes.TODAY) {
                launchSingleTop = true
            }
        }
    }

    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            (context as? MainActivity)?.briefNotifier?.schedule()
        }
    }
    SideEffect {
        (context as? MainActivity)?.notifLauncher = notifLauncher
    }

    val captureState by capture.state.collectAsStateWithLifecycle()
    val deviceOwnerName by workspace.deviceOwnerName.collectAsStateWithLifecycle()
    var hasRequestedNotifications by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(captureState.reminderAtMs) {
        if (captureState.reminderAtMs != null && !hasRequestedNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasRequestedNotifications = true
            onRequestNotificationsPermission()
        }
    }

    CaptureHost(capture, rootViewModel, workspaceState.contacts, snackbarHostState,
        privateMode = workspaceState.hidden, workspaceReady = !workspaceState.loading && workspaceState.error == null,
        senderName = deviceOwnerName) { captureActions ->
    BoxWithConstraints {
    val useRail = maxWidth >= 600.dp
    Scaffold(
        bottomBar = {
            if (currentRoute in setOf(Routes.HOME, Routes.TODAY, Routes.CONTACTS)) {
                Column {
                    NoteBar(onTextClick = captureActions.text, onCameraClick = captureActions.photo,
                        onMicClick = captureActions.voice, hasDraft = captureState.text.isNotBlank())
                    if (!useRail) WorkspaceNavigation(navController, currentRoute)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (useRail && currentRoute in setOf(Routes.TODAY, Routes.HOME, Routes.CONTACTS)) {
                NavigationRail(modifier = Modifier.fillMaxHeight()) {
                    listOf(Triple(Routes.TODAY, "Today", Icons.Outlined.Today),
                        Triple(Routes.HOME, "Instructions", Icons.Outlined.Checklist),
                        Triple(Routes.CONTACTS, "Contacts", Icons.Outlined.People)).forEach { (route, label, icon) ->
                        NavigationRailItem(
                            modifier = Modifier.testTag("nav_$route"), selected = currentRoute == route,
                            onClick = { navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            } },
                            icon = { Icon(icon, contentDescription = null) }, label = { Text(label) },
                        )
                    }
                }
            }
            NavHost(
                navController = navController,
                startDestination = Routes.TODAY,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                listOf(Routes.TODAY to WorkspaceTab.TODAY, Routes.HOME to WorkspaceTab.INSTRUCTIONS,
                    Routes.CONTACTS to WorkspaceTab.CONTACTS).forEach { (route, tab) ->
                    composable(route) {
                        WorkspaceScreen(
                            tab = tab, state = workspaceState, date = today, busy = workspaceBusy,
                            onSettings = { showSettings = true },
                            onCapture = captureActions.text,
                            onInstruction = { selectedInstructionId = it },
                            onContact = { navController.navigate(Routes.person(it)) },
                            onAddContact = { showAddContact = true },
                            onImportContact = { showImportContact = true },
                            onOpenInstructions = { navController.navigate(Routes.HOME) { launchSingleTop = true } },
                            onComplete = { workspace.complete(it) {} },
                            onRetry = workspace::retry,
                        )
                    }
                }
                composable(Routes.PERSON) { entry ->
                    val personId = entry.arguments?.getString("personId") ?: return@composable
                    HomeScreenPersonDetail(
                        personId = personId,
                        onBack = { navController.popBackStack() },
                        onOpenLinkedPerson = { id -> navController.navigate("person/$id") },
                        onCaptureForPerson = capture::openForContact,
                        onOpenInstruction = { selectedInstructionId = it },
                        onEditContact = { editContactId = personId },
                    )
                }
                // v2.0 T3-2: recovery phrase screen. Reachable
                // from Settings → Privacy → Recovery phrase.
                // The screen manages its own FLAG_SECURE flag
                // via [com.kaavalan.note.ui.privacy.FlagSecureEffect].
                composable(Routes.RECOVERY_PHRASE) {
                    RecoveryPhraseScreen(
                        onClose = { navController.popBackStack() },
                    )
                }
                // v2.0 T3-3: threat model screen. Reachable
                // from Settings → Privacy → Threat model.
                composable(Routes.THREAT_MODEL) {
                    ThreatModelScreen(
                        onClose = { navController.popBackStack() },
                    )
                }
                // v1.9.12 (A9 wire-up): the changelog screen.
                // Reachable from Settings → Privacy → What's
                // new. The screen reads assets/changelog.json
                // and marks the current version as "seen" on
                // dismiss.
                composable(Routes.CHANGELOG) {
                    com.kaavalan.note.features.changelog.ChangelogScreen(
                        onDismiss = { navController.popBackStack() },
                    )
                }
                // v2.0 (PM rating): the in-app audit-log
                // viewer. Reachable from Settings → Privacy →
                // Audit log. The chain has been writing rows
                // since v1.8.0 (see AuditChainWriter); v2.0
                // surfaces them.
                composable(Routes.AUDIT_LOG) {
                    com.kaavalan.note.features.audit.AuditLogScreen(
                        onClose = { navController.popBackStack() },
                    )
                }
                // v2.0.2 (PM rating): the About screen.
                // Reachable from Settings → Privacy → About.
                composable(Routes.ABOUT) {
                    com.kaavalan.note.features.about.AboutScreen(
                        onClose = { navController.popBackStack() },
                    )
                }
                // v2.1.1 (QA P1-#4): the sync-conflict list + diff
                // screens are removed entirely. v2.0+ is vault-mode
                // (no cloud), the conflict table is always empty,
                // and the resolve buttons on the diff screen were
                // no-ops that only dismissed the dialog without
                // touching the DAO. The "Cloud sync is not enabled
                // in this build" string confirmed the screen is
                // dead. The SyncConflictEntity / SyncConflictDao /
                // `sync_conflicts` Room table are intentionally
                // retained for the future cloud-sync build.
            }
        }
    }

    }
    }
    val selectedInstruction = workspaceState.instructions.firstOrNull { it.id == selectedInstructionId }
    if (selectedInstruction != null) {
        com.kaavalan.note.ui.components.InstructionDetailSheet(
            instruction = selectedInstruction,
            onDismiss = { selectedInstructionId = null },
            onMarkDone = { workspace.complete(selectedInstruction.id) { selectedInstructionId = null } },
            onDrop = { workspace.close(selectedInstruction.id) { selectedInstructionId = null } },
            onReopen = { workspace.reopen(selectedInstruction.id) { selectedInstructionId = null } },
            onReminderChanged = { if (it != null) onRequestNotificationsPermission(); workspace.remind(selectedInstruction.id, it) },
            busy = workspaceBusy,
            contactName = workspaceState.contacts.firstOrNull { it.id == selectedInstruction.personId }?.name,
            contacts = workspaceState.contacts,
            privateMode = workspaceState.hidden,
            onEdit = { text, direction, personId, deadline, saved -> workspace.edit(selectedInstruction.id, text, direction, personId, deadline, saved) },
            onAddUpdate = { text, status, followUp, saved ->
                if (followUp != null) onRequestNotificationsPermission()
                workspace.addUpdate(selectedInstruction.id, text, status, followUp, saved)
            },
            onShare = { shareInstructionId = selectedInstruction.id; selectedInstructionId = null },
            onPrivacy = { privacyInstructionId = selectedInstruction.id; selectedInstructionId = null },
        )
    }
    workspaceState.contacts.firstOrNull { it.id == editContactId }?.let { person ->
        EditContactSheet(person, workspaceBusy, { editContactId = null }) { name, rank, station, phone ->
            workspace.editContact(person.id, name, rank, station, phone) { editContactId = null }
        }
    }
    workspaceState.instructions.firstOrNull { it.id == shareInstructionId }?.let { item ->
        com.kaavalan.note.ui.home.NudgeSheet(item, workspaceState.contacts.firstOrNull { it.id == item.personId }, { shareInstructionId = null })
    }
    workspaceState.instructions.firstOrNull { it.id == privacyInstructionId }?.let { item ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { privacyInstructionId = null },
            title = { androidx.compose.material3.Text("Private instruction record") },
            text = { androidx.compose.material3.Text("This instruction and its updates are stored in your encrypted local database. Updates are not sent to contacts. Share follow-up sends only the draft you review. Backups and exports may include this record.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { privacyInstructionId = null }) { androidx.compose.material3.Text("Got it") } })
    }
    if (showAddContact) {
        com.kaavalan.note.ui.home.AddPersonSheet(
            onSave = { name, rank, station ->
                workspace.addContact(name, rank, station) { showAddContact = false }
            },
            onDismiss = { showAddContact = false },
            isSaving = workspaceBusy,
        )
    }
    if (showImportContact) {
        com.kaavalan.note.ui.hierarchy.ContactPickerSheet(
            contactSyncService = workspace.contactSyncService,
            onPicked = { name, phone -> workspace.importContact(name, phone) { showImportContact = false } },
            onDismiss = { showImportContact = false },
            isSaving = workspaceBusy,
        )
    }
    if (showSettings) {
        SettingsSheet(
            onDismiss = { showSettings = false },
            onVaultExport = { showSettings = false; showVaultExport = true },
            onVaultImport = { showSettings = false; showVaultImport = true },
            onOpenRecoveryPhrase = {
                showSettings = false
                navController.navigate(Routes.RECOVERY_PHRASE)
            },
            onOpenThreatModel = {
                showSettings = false
                navController.navigate(Routes.THREAT_MODEL)
            },
            // v1.9.12 (A9 wire-up): the changelog screen is
            // reachable from Settings. The v1.6.0 design rule
            // forbids auto-showing it at first launch as a
            // modal; the Settings row is the canonical entry
            // point. The screen reads assets/changelog.json
            // and marks the current version as "seen" on
            // dismiss.
            onOpenChangelog = {
                showSettings = false
                navController.navigate(Routes.CHANGELOG)
            },
            // v2.0 (PM rating): the in-app audit-log viewer
            // is reachable from Settings. The chain has
            // been writing rows since v1.8.0; v2.0 surfaces
            // them.
            onOpenAuditLog = {
                showSettings = false
                navController.navigate(Routes.AUDIT_LOG)
            },
            // v2.0.2 (PM rating): the About screen is
            // reachable from Settings. The version is
            // already in the storage card; this is the
            // canonical build-info + privacy-posture
            // surface.
            onOpenAbout = {
                showSettings = false
                navController.navigate(Routes.ABOUT)
            },
            // v2.0.0: onOpenSyncConflicts removed — the sync
            // queue is a no-op stub (no cloud). The Settings
            // sheet no longer links to the sync-conflict
            // screen; the screen file is kept for forward-
            // compat but is dormant.
        )
    }
    if (showVaultExport) {
        VaultExportSheet(
            onDismiss = { showVaultExport = false },
            onExported = { showVaultExport = false },
        )
    }
    if (showVaultImport) {
        VaultImportSheet(
            onDismiss = { showVaultImport = false },
            onImported = { showVaultImport = false },
        )
    }
}

/** Material navigation owns selected semantics and system navigation-bar insets. */
@Composable
private fun WorkspaceNavigation(navController: NavHostController, currentRoute: String) {
    NavigationBar(tonalElevation = 0.dp) {
        listOf(
            Triple(Routes.TODAY, "Today", Icons.Outlined.Today),
            Triple(Routes.HOME, "Instructions", Icons.Outlined.Checklist),
            Triple(Routes.CONTACTS, "Contacts", Icons.Outlined.People),
        ).forEach { (route, label, icon) ->
            NavigationBarItem(
                modifier = Modifier.testTag("nav_$route"),
                selected = currentRoute == route,
                onClick = {
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun HomeScreenPersonDetail(
    personId: String,
    onBack: () -> Unit,
    onOpenLinkedPerson: (String) -> Unit = {},
    onCaptureForPerson: ((String) -> Unit)? = null,
    onOpenInstruction: (String) -> Unit,
    onEditContact: () -> Unit,
) {
    val vm: com.kaavalan.note.ui.home.PersonDetailViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    com.kaavalan.note.ui.home.PersonDetailScreen(
        personId = personId,
        onBack = onBack,
        onOpenLinkedPerson = onOpenLinkedPerson,
        onCaptureForPerson = onCaptureForPerson,
        onOpenInstruction = onOpenInstruction,
        onEditContact = onEditContact,
        viewModel = vm,
    )
}
