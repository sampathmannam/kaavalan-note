package com.kaavalan.note.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaavalan.note.R
import com.kaavalan.note.data.preferences.ThemeMode
import com.kaavalan.note.data.preferences.KaavalanPreferences
import com.kaavalan.note.data.tags.Tag
import com.kaavalan.note.data.tags.TagKind
import com.kaavalan.note.data.vault.VaultMode
import com.kaavalan.note.features.tags.colorForKind
import com.kaavalan.note.features.tags.parseHex
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    onVaultExport: () -> Unit = {},
    onVaultImport: () -> Unit = {},
    onOpenRecoveryPhrase: () -> Unit = {},
    onOpenThreatModel: () -> Unit = {},
    // v1.9.12 (A9 wire-up): the changelog screen is reachable
    // from Settings, not auto-shown at first launch (the v1.6.0
    // design rule forbids launch-time modals). The callback is
    // a no-op default so tests can construct the sheet without
    // a navigation host.
    onOpenChangelog: () -> Unit = {},
    // v2.0 (PM rating): the in-app audit-log viewer is
    // reachable from Settings. The chain has been writing
    // rows since v1.8.0; v2.0 surfaces them.
    onOpenAuditLog: () -> Unit = {},
    // v2.0.2 (PM rating): the About screen is reachable
    // from Settings. The version is already in the storage
    // card; this is the canonical build-info + privacy-
    // posture surface.
    onOpenAbout: () -> Unit = {},
    // v1.9.0 (PROD-READINESS-P3-P1-#8 + #9):
    // the Drive backup / restore rows use
    // [rememberLauncherForActivityResult]
    // inside the Settings sheet directly.
    // The MainActivity callbacks for the
    // drive / restore are no-ops (the
    // sheet's launchers fire the system
    // file picker).
    // v1.9.0 (PROD-READINESS-P3-P1-#3): the
    // update channel + crash log rows are
    // handled by the sheet's own VM.
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val signingOut by viewModel.signingOut.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    // v1.9.0 (PROD-READINESS-P3-P1-#3): the
    // in-app update channel. The "Check for
    // updates" row in the Settings sheet
    // calls [viewModel.checkForUpdates]
    // directly. The result is exposed via
    // [SettingsViewModel.updateCheckResult]
    // and rendered as a snackbar via
    // [LaunchedEffect] below.
    val updateCheckInProgress by viewModel.updateCheckInProgress.collectAsStateWithLifecycle()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    // v2.1.0 (PM rating): the Drive backup passphrase
    // prompt + the restore list dialog. The user
    // enters the 12-word recovery phrase; the VM
    // derives the AES key and encrypts the backup
    // blob. For restore, the user picks from a
    // list of available Drive backups.
    // v2.1.x (BUG FIX, data-loss): these must be declared
    // before the driveBackupEvent LaunchedEffect below --
    // that collector's `when` branches assign into all
    // five of them, and Kotlin resolves a local `var`
    // reference by textual position even inside a lambda
    // defined earlier in the same function, so declaring
    // them after the LaunchedEffect fails to compile
    // ("Unresolved reference"). The two flags were
    // previously declared later in this function (after
    // this LaunchedEffect) and never read by any
    // composable -- no dialog ever rendered, so "Back up
    // now" / "Restore from backup" were dead no-ops even
    // when signed in. The other three carry the state the
    // dialogs further down need: the list of Drive backups
    // (from the [SettingsViewModel.DriveBackupEvent.BackupsListed]
    // event), which one the user picked to restore, and
    // whether the last restore attempt's passphrase was
    // wrong (so the dialog stays open for a retry,
    // mirroring [PinDialog]'s `enterPinWrong`).
    var showDriveBackupPassphrasePrompt by remember { mutableStateOf(false) }
    var showDriveRestoreList by remember { mutableStateOf(false) }
    var driveBackupFiles by remember {
        mutableStateOf<List<com.kaavalan.note.data.backup.DriveRestApi.DriveFile>>(emptyList())
    }
    var driveRestoreTarget by remember {
        mutableStateOf<com.kaavalan.note.data.backup.DriveRestApi.DriveFile?>(null)
    }
    var driveRestoreWrongPassphrase by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.updateCheckResult.collect { info ->
            val msg = when (info) {
                is com.kaavalan.note.data.update.UpdateChecker.UpdateInfo.UpToDate ->
                    "You are on the latest version"
                is com.kaavalan.note.data.update.UpdateChecker.UpdateInfo.UpdateAvailable ->
                    "Kaavalan note ${info.latestVersion} is available. You are on ${info.currentVersion}."
                is com.kaavalan.note.data.update.UpdateChecker.UpdateInfo.Unavailable ->
                    "Could not reach the update server. Try again later."
            }
            snackbarHostState.showSnackbar(msg)
        }
    }
    // v2.1.0 (PM rating): the Drive backup event
    // collector. Renders a snackbar for success / error
    // outcomes from the [DriveBackupManager] round-trip.
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.driveBackupEvent.collect { event ->
            // v2.1.x (BUG FIX, data-loss): this `when` used to end in
            // `else -> null`, so BackupsListed / SignInRequired /
            // PassphraseRequired were silently dropped -- on top of
            // the fact that nothing ever called googleDriveBackUpNow
            // or googleDriveRestore in the first place. Listing every
            // DriveBackupEvent subtype (no `else`) makes the `when`
            // exhaustive, so a future new event fails to compile
            // here instead of silently vanishing again.
            val msg = when (event) {
                is com.kaavalan.note.ui.settings.SettingsViewModel.DriveBackupEvent.BackUpSuccess -> {
                    showDriveBackupPassphrasePrompt = false
                    "Backed up to Google Drive."
                }
                is com.kaavalan.note.ui.settings.SettingsViewModel.DriveBackupEvent.BackUpFailed -> {
                    showDriveBackupPassphrasePrompt = false
                    "Backup failed: ${event.reason}"
                }
                is com.kaavalan.note.ui.settings.SettingsViewModel.DriveBackupEvent.RestoreSucceeded -> {
                    driveRestoreTarget = null
                    showDriveRestoreList = false
                    driveRestoreWrongPassphrase = false
                    "Restored ${event.rows} rows from Google Drive."
                }
                is com.kaavalan.note.ui.settings.SettingsViewModel.DriveBackupEvent.RestoreFailed -> {
                    driveRestoreWrongPassphrase = false
                    "Restore failed: ${event.reason}"
                }
                is com.kaavalan.note.ui.settings.SettingsViewModel.DriveBackupEvent.WrongPassphrase -> {
                    // Keep the restore passphrase dialog open so the
                    // user can retype it, instead of just dropping
                    // the event on the floor.
                    driveRestoreWrongPassphrase = true
                    null
                }
                is com.kaavalan.note.ui.settings.SettingsViewModel.DriveBackupEvent.BackupsListed -> {
                    // Feeds the "Choose a backup to restore" dialog
                    // below.
                    driveBackupFiles = event.files
                    null
                }
                is com.kaavalan.note.ui.settings.SettingsViewModel.DriveBackupEvent.SignInRequired -> {
                    showDriveBackupPassphrasePrompt = false
                    driveRestoreTarget = null
                    "Please sign in to Google Drive first."
                }
                is com.kaavalan.note.ui.settings.SettingsViewModel.DriveBackupEvent.PassphraseRequired ->
                    "Enter your recovery phrase to continue."
            }
            msg?.let { snackbarHostState.showSnackbar(it) }
        }
    }
    val appVersion = viewModel.appVersion
    // v1.6.1: the "Models" section is gone. The on-device
    // LLM and the whisper.cpp voice model are both removed.
    // Voice capture uses the system SpeechRecognizer (no
    // model file to download). The capture sheet has no
    // Extract step. The Settings sheet is now the About +
    // Data + Theme + Privacy surface it was in v1.5.4
    // before the Models section landed.
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    // v1.5.1 (VAULT-007): the destructive action (erases ALL local
    // data) used to fire on a single button tap. In vault mode the
    // user has no cloud backup, so a stray tap means losing every
    // note forever. Require an explicit confirmation.
    var showEraseConfirmation by remember { mutableStateOf(false) }
    var plainExportError by remember { mutableStateOf<String?>(null) }
    var plainExportOk by remember { mutableStateOf(false) }
    // v1.6.2: the developer-only "Load test data" state.
    var fixtureLoading by remember { mutableStateOf(false) }
    var fixtureLoadReport by remember { mutableStateOf<com.kaavalan.note.data.dev.FixtureLoader.LoadReport?>(null) }
    var fixtureLoadError by remember { mutableStateOf<String?>(null) }

    // v1.7.3 (P1-C): selected plain-export format. CSV by default
    // (preserves the v1.7.2 behaviour). The radio group in the
    // Data section lets the user see which format will be used
    // before tapping Export. State is local to the sheet — the
    // selection does not persist across sheet reopens (the user
    // re-chooses each time, which is the right default for a
    // destructive action: "did I really mean JSON this time?").
    var selectedPlainFormat by remember { mutableStateOf("csv") }

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val r = viewModel.exportPlain(uri, "text/csv")
                if (r.isSuccess) { plainExportOk = true; plainExportError = null }
                else { plainExportError = r.exceptionOrNull()?.message }
            }
        }
    }
    // v2.0.1 (PM rating): the inverse — pick a CSV/JSON
    // from SAF (OpenDocument contract) and call
    // [viewModel.importPlain]. MIME is ignored; the
    // importer branches on the first non-whitespace
    // character.
    var plainImportOk by remember { mutableStateOf<String?>(null) }
    var plainImportError by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val r = viewModel.importPlain(uri)
                if (r.isSuccess) {
                    val report = r.getOrThrow()
                    plainImportOk =
                        "Imported ${report.total} rows " +
                        "(${report.peopleInserted + report.peopleUpdated} people, " +
                        "${report.instructionsInserted + report.instructionsUpdated} instructions, " +
                        "${report.tagsInserted + report.tagsUpdated} tags)"
                    plainImportError = null
                } else {
                    plainImportError = r.exceptionOrNull()?.message
                }
            }
        }
    }
    val jsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val r = viewModel.exportPlain(uri, "application/json")
                if (r.isSuccess) { plainExportOk = true; plainExportError = null }
                else { plainExportError = r.exceptionOrNull()?.message }
            }
        }
    }
    // v1.9.0 (PROD-READINESS-P3-P1-#8): the
    // "Back up to Google Drive" launcher.
    // The system file picker shows Google
    // Drive, Dropbox, local storage, etc.
    // The user picks a folder; the app
    // copies the latest cached backup to
    // the chosen content:// URI.
    val driveBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val latest = viewModel.backupManager.listBackups()
                    .maxByOrNull { it.lastModified() }
                if (latest != null) {
                    runCatching {
                        com.kaavalan.note.data.export.DriveBackup.writeToUri(ctx, latest, uri)
                    }
                }
            }
        }
    }
    // v1.9.0 (PROD-READINESS-P3-P1-#9): the
    // "Restore from backup" launcher. The
    // user picks a backup file; the app
    // reads it into a temp file and
    // applies it to the local DB.
    val restoreBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val tempFile = runCatching {
                    com.kaavalan.note.data.export.DriveBackup.readFromUri(ctx, uri)
                }.getOrNull() ?: return@launch
                runCatching {
                    viewModel.backupManager.restore(tempFile)
                    tempFile.delete()
                }
            }
        }
    }
    // v2.0 T3-1: deniable-vault dialogs. The three state vars
    // drive the "set PIN" / "enter PIN to unlock" / "confirm
    // switch to hidden" flows. They are mutually exclusive
    // (only one is open at a time) and each is dismissed by
    // its own confirm/dismiss button.
    var showSetPinDialog by remember { mutableStateOf(false) }
    var showEnterPinDialog by remember { mutableStateOf(false) }
    var showSwitchToHiddenConfirm by remember { mutableStateOf(false) }
    var enterPinWrong by remember { mutableStateOf(false) }
    val vaultMode by viewModel.vaultMode.collectAsStateWithLifecycle()
    val hasVaultPin by viewModel.hasVaultPin.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        // v1.9.0 (PROD-READINESS-P3-P1-#3): the
        // snackbar host for the update-check
        // result. The host is rendered at the
        // bottom of the sheet (above the
        // content) so the snackbar appears as
        // an overlay on the sheet.
        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
        )
        // v1.6.2: the Settings sheet is now longer (the
        // Developer section + its Load test data button were
        // added in v1.6.2). On a small screen the previous
        // non-scrolling Column clipped the bottom rows (the
        // Erase all data button was unreachable on a 1080x2400
        // emulator). Wrap the inner Column in a verticalScroll
        // so the entire content is reachable; the sheet itself
        // stays at full height (`skipPartiallyExpanded = true`)
        // so the user always sees the top of the Settings list.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // v1.7.1 (P1 St1): a visible Close button in the
            // top-right of the sheet. ModalBottomSheet
            // supports scrim-tap and swipe-down dismissal,
            // but neither affordance is discoverable on a
            // first read — the sheet covers the bottom nav
            // so the user has no other visual cue. The X
            // button is a tappable icon at the top-right
            // of the title row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    // v2.1.2 (test-infra): the bottom nav's Settings tab
                    // label is still composed underneath the modal sheet,
                    // so `onNodeWithText("Settings")` matches two nodes
                    // once the sheet is open (BottomNavTabSwitchTest hit
                    // this: "Expected at most 1 node but found 2"). A
                    // testTag has no runtime or a11y effect -- it exists
                    // only for test targeting -- and uniquely identifies
                    // this specific header regardless of what other UI
                    // text happens to say "Settings".
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings_sheet_title"),
                )
                IconButton(
                    onClick = onDismiss,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.settings_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            TagsSection(
                tags = tags,
                onAdd = viewModel::addFreeTag,
            )

            // v2.0 T3-1 + T3-2 + T3-3: the Privacy section.
            // Four rows: vault mode, vault PIN, recovery
            // phrase, threat model. Each row is a tappable
            // affordance that opens either a dialog
            // (PIN-related) or a dedicated screen (recovery
            // phrase, threat model). The vault-mode row is
            // the only one that takes effect immediately;
            // the others navigate.
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                text = stringResource(R.string.settings_section_privacy),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            PrivacyRow(
                label = stringResource(R.string.settings_vault_mode),
                value = when (vaultMode) {
                    VaultMode.Visible -> stringResource(R.string.settings_vault_mode_visible)
                    VaultMode.Hidden -> stringResource(R.string.settings_vault_mode_hidden)
                },
                explainer = if (hasVaultPin) {
                    stringResource(R.string.settings_vault_mode_explainer)
                } else {
                    stringResource(R.string.settings_vault_mode_explainer_no_pin)
                },
                onClick = {
                    if (vaultMode == VaultMode.Visible) {
                        showSwitchToHiddenConfirm = true
                    } else {
                        // Hidden -> Visible. Requires PIN.
                        if (hasVaultPin) {
                            showEnterPinDialog = true
                        } else {
                            // No PIN set; force the user to
                            // set one first by opening the
                            // PIN dialog.
                            showSetPinDialog = true
                        }
                    }
                },
            )
            PrivacyRow(
                label = stringResource(R.string.settings_vault_pin),
                value = if (hasVaultPin) {
                    stringResource(R.string.settings_vault_pin_value_set)
                } else {
                    stringResource(R.string.settings_vault_pin_unset)
                },
                explainer = null,
                onClick = { showSetPinDialog = true },
            )
            val hasRecoveryPhrase by viewModel.hasRecoveryPhrase.collectAsStateWithLifecycle()
            PrivacyRow(
                label = stringResource(R.string.settings_recovery_phrase),
                value = if (hasRecoveryPhrase) {
                    stringResource(R.string.settings_recovery_phrase_value_set)
                } else {
                    stringResource(R.string.settings_recovery_phrase_value_unset)
                },
                explainer = stringResource(R.string.settings_recovery_phrase_explainer),
                onClick = onOpenRecoveryPhrase,
            )
            PrivacyRow(
                label = stringResource(R.string.settings_threat_model),
                value = stringResource(R.string.settings_threat_model_value),
                explainer = null,
                onClick = onOpenThreatModel,
            )
            // v1.9.12 (A9 wire-up): the changelog is reachable
            // from Settings so the user can review release
            // notes at any time. This row is the canonical
            // "What's new in this build?" surface (the v1.6.0
            // design rule forbids auto-showing it at first
            // launch as a modal).
            PrivacyRow(
                label = stringResource(R.string.settings_changelog),
                value = "v${viewModel.appVersion.name} (build ${viewModel.appVersion.code})",
                explainer = stringResource(R.string.settings_changelog_explainer),
                onClick = onOpenChangelog,
            )
            // v2.0 (PM rating): the audit-log viewer is
            // reachable from Settings. The chain has been
            // writing rows since v1.8.0; v2.0 surfaces them
            // with a "verify chain" action that runs the
            // SHA-256 hash check.
            PrivacyRow(
                label = stringResource(R.string.settings_audit_log),
                value = stringResource(R.string.settings_audit_log_value),
                explainer = stringResource(R.string.settings_audit_log_explainer),
                onClick = onOpenAuditLog,
            )
            // v2.0.2 (PM rating): the About screen. The
            // version is already in the storage card;
            // this is the canonical build-info + privacy-
            // posture surface.
            PrivacyRow(
                label = stringResource(R.string.settings_about),
                value = "v${viewModel.appVersion.name}",
                explainer = stringResource(R.string.settings_about_explainer),
                onClick = onOpenAbout,
            )

            // v2.1.0 (PM rating): the Google Drive backup
            // surface. The "WhatsApp-style" daily auto-backup
            // + cross-device restore. The user signs in to
            // Google, types the recovery phrase once (the
            // worker re-uses the hash), and the daily
            // WorkManager job takes it from there.
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                text = stringResource(R.string.settings_drive_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.settings_drive_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val driveSignedIn by viewModel.googleDriveSignedIn.collectAsStateWithLifecycle()
            if (driveSignedIn) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { showDriveBackupPassphrasePrompt = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_drive_back_up_now))
                }
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.TextButton(
                    onClick = {
                        // v2.1.x (BUG FIX, data-loss): this used to
                        // only call googleDriveListBackups() -- the
                        // BackupsListed result was dropped by the
                        // collector's `else -> null` and no dialog
                        // ever showed the list. Open the dialog and
                        // clear any stale list from a previous open.
                        driveBackupFiles = emptyList()
                        showDriveRestoreList = true
                        viewModel.googleDriveListBackups()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_drive_restore))
                }
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.googleDriveSignOut() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_drive_sign_out))
                }
            } else {
                androidx.compose.material3.Button(
                    onClick = { viewModel.googleDriveSignIn() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_drive_sign_in))
                }
            }

            val stuckCount by viewModel.stuckOutboxCount.collectAsStateWithLifecycle()
            if (stuckCount > 0) {
                StuckOutboxCard(
                    count = stuckCount,
                    onRetry = viewModel::retryStuckOutbox,
                )
            }

            // v2.0.2 (PM rating): the database-error banner.
            // Renders only when the preflight detected a
            // runtime DB open failure. The CTA is the
            // existing "Erase all data" flow (with a
            // confirmation dialog) — the same path the
            // runbook documents for "the app is broken, I
            // need a fresh start".
            val dbCorrupt by viewModel.databaseCorrupt.collectAsStateWithLifecycle()
            if (dbCorrupt) {
                DatabaseErrorCard(
                    onErase = {
                        showEraseConfirmation = true
                    },
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            // v1.6.1: the "Models" section is removed. The
            // on-device LLM (llama.cpp) and the whisper.cpp
            // voice model are both gone. Voice capture uses
            // the system SpeechRecognizer; the capture sheet
            // has no Extract step. The Settings sheet is now
            // the About + Data + Theme + Privacy surface it
            // was in v1.5.4 before the Models section landed.

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            // v2.0 (Tier 1.4): the theme switcher. A segmented
            // button row that maps `ThemeMode` to the user's
            // choice (System / Light / Dark). The selection
            // persists via [KaavalanPreferences.setThemeMode] and
            // the root composable observes the same flow.
            Text(
                text = stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            ThemeRow(
                current = themeMode,
                onChange = viewModel::setThemeMode,
            )
            Spacer(Modifier.height(8.dp))

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            // v2.0 (Tier 1.1 + 1.7): the Data section. Three
            // rows: Export encrypted vault (opens a sheet that
            // collects a passphrase), Import encrypted vault
            // (opens a sheet that collects a passphrase + the
            // file), and Export as CSV or JSON (uses a SAF
            // CreateDocument with custom MIME).
            Text(
                text = stringResource(R.string.settings_section_data),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Button(
                onClick = onVaultExport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_vault_export))
            }
            Button(
                onClick = onVaultImport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_vault_import))
            }
            Text(
                text = stringResource(R.string.settings_plain_export),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // v1.7.3 (P1-C): radio + label tappable as a unit.
                // The Row's onClick toggles the selected format AND
                // launches the export directly so the previous
                // "one-tap to export" UX is preserved. The
                // RadioButton is just a visible indicator of the
                // current selection; tap-the-row to switch + export.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            plainExportError = null
                            plainExportOk = false
                            selectedPlainFormat = "csv"
                            csvLauncher.launch("kaavalan-note-${ts()}.csv")
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = selectedPlainFormat == "csv",
                        onClick = null,  // Row handles the click
                    )
                    Text(
                        text = stringResource(R.string.plain_export_csv),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            plainExportError = null
                            plainExportOk = false
                            selectedPlainFormat = "json"
                            jsonLauncher.launch("kaavalan-note-${ts()}.json")
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = selectedPlainFormat == "json",
                        onClick = null,
                    )
                    Text(
                        text = stringResource(R.string.plain_export_json),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (plainExportOk) {
                Text(
                    text = stringResource(R.string.plain_export_success),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (plainExportError != null) {
                Text(
                    text = stringResource(R.string.plain_export_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // v2.0.1 (PM rating): the inverse of the export
            // block above. Tapping the row opens the SAF
            // file picker (no MIME filter — the importer
            // branches on the first non-whitespace char).
            // The result snackbar reports inserted + updated
            // counts. Re-importing the same file is
            // idempotent (upsert by id).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        plainImportError = null
                        plainImportOk = null
                        // Empty array = "any type"; the
                        // importer reads the bytes and
                        // decides CSV vs JSON.
                        importLauncher.launch(arrayOf("*/*"))
                    }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.plain_import_button),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (plainImportOk != null) {
                Text(
                    text = plainImportOk!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (plainImportError != null) {
                Text(
                    text = stringResource(R.string.plain_import_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // v1.8.0 (PROD-READINESS-P0-#1): the "Back up now"
            // row. Tapping it enqueues a one-shot [BackupWorker]
            // that writes a JSON snapshot to the app's private
            // filesDir. The work is async; the user gets a
            // confirmation message and can keep using the app.
            // The daily periodic schedule (separate row in the
            // v1.8.0 release notes; here it just shows the
            // current status) is also wired in
            // [com.kaavalan.note.KaavalanApplication.onCreate] via
            // [WorkManagerInitializer.scheduleBackup].
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        viewModel.backupNow()
                        plainExportOk = true
                        plainExportError = null
                    }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Back up now",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "(writes to app storage)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (plainExportOk) {
                Text(
                    text = "Backup queued. The file will appear in the app's private storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // v1.9.0 (PROD-READINESS-P3-P1-#3): the
            // "Check for updates" row. The tap
            // calls [viewModel.checkForUpdates]
            // directly; the result is surfaced
            // via the [updateCheckInProgress] +
            // [snackbarHostState] state at the top
            // of the Settings sheet.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !updateCheckInProgress) {
                        viewModel.checkForUpdates()
                    }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (updateCheckInProgress) "Checking…"
                           else "Check for updates",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "v${appVersion.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // v2.1.1 (PM rating): the v1.9.0
            // "Save backup to a folder..." +
            // "Restore from backup" rows are REMOVED.
            // Both rows re-used the same string
            // resources as the v2.1.0 Google Drive
            // section (settings_drive_title,
            // settings_drive_restore) and rendered
            // directly below it, so the Settings
            // sheet showed two "Google Drive backup"
            // sections. The v2.1.0 Drive section
            // (above) is the canonical "Back up to
            // Google Drive" / "Restore from Google
            // Drive" surface. The SAF launchers
            // (driveBackupLauncher + restoreBackupLauncher)
            // are still declared for future use, but
            // no UI invokes them.
            // v2.1.1 (QA P1-#4): the "Sync conflicts" row
            // and its backing screen were removed. Vault-mode
            // builds have no cloud sync, the conflict table
            // is always empty, and the diff screen's resolve
            // buttons were no-ops that only dismissed the
            // dialog. A future cloud-sync build can re-add
            // the row alongside the re-introduced
            // SyncConflictScreens file.
            Spacer(Modifier.height(8.dp))

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            // v1.5.3 (VAULT-008): the About section. App version,
            // storage counts, data mode. No interaction, just
            // info — these are read-only debug-style fields the
            // user can use to verify which build is on the device
            // and how much is in the vault.
            Text(
                text = stringResource(R.string.settings_section_about),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            AboutRow(
                label = stringResource(R.string.settings_app_version),
                value = stringResource(
                    R.string.settings_app_version_value,
                    appVersion.name,
                    appVersion.code,
                ),
            )
            // v1.9.0 (PROD-READINESS-P3-P1-#4): the
            // support row. Tapping it opens the
            // system email composer with a
            // pre-filled subject (Kaavalan note {version}
            // support) and body (version + device
            // + Android). The row uses
            // [androidx.compose.ui.platform.LocalContext]
            // for the Intent; the handler chain
            // (mailto: → Gmail / Outlook / system
            // default) is whatever the user has
            // installed.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_SENDTO,
                        ).apply {
                            data = android.net.Uri.parse(
                                "mailto:" + ctx.getString(R.string.settings_support_email) +
                                    "?subject=" + java.net.URLEncoder.encode(
                                        ctx.getString(
                                            R.string.settings_support_email_subject,
                                            appVersion.name,
                                        ),
                                        "UTF-8",
                                    ) +
                                    "&body=" + java.net.URLEncoder.encode(
                                        ctx.getString(
                                            R.string.settings_support_email_body,
                                            appVersion.name,
                                            appVersion.code,
                                            android.os.Build.MANUFACTURER + " " +
                                                android.os.Build.MODEL,
                                            "Android " + android.os.Build.VERSION.RELEASE,
                                        ),
                                        "UTF-8",
                                    ),
                            )
                        }
                        ctx.startActivity(intent)
                    }
                    .padding(vertical = 8.dp, horizontal = 4.dp)
                    .semantics {
                        contentDescription = ctx.getString(R.string.settings_support_email_cd)
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_support),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.settings_support_email),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // v1.9.9 (A10 audit fix): the "Report a
            // problem" row. Always visible. One tap
            // opens the email composer via `mailto:`
            // with the most recent crash log (if any)
            // embedded in the body. Distinct from
            // the "Support" row (general question) and
            // the conditional "Share crash log" row
            // (system share sheet, file attached). See
            // [com.kaavalan.note.ui.util.ReportProblemIntent]
            // for the three flows and why they coexist.
            //
            // The resource lookups happen here (Compose
            // composable scope) rather than inside the
            // helper because the project's Robolectric
            // tests run without `includeAndroidResources`
            // (see the
            // [com.kaavalan.note.ui.util.ReportProblemIntent]
            // docstring for the rationale). The helper
            // takes plain strings so it's testable.
            val supportEmail = stringResource(R.string.settings_support_email)
            val reportSubjectTemplate = stringResource(
                R.string.settings_report_problem_subject,
            )
            val reportBodyNoCrashTemplate = stringResource(
                R.string.settings_report_problem_body_no_crash,
            )
            val reportBodyWithCrashTemplate = stringResource(
                R.string.settings_report_problem_body_with_crash,
            )
            val hasCrashLog = remember {
                com.kaavalan.note.ui.util.CrashLog.mostRecent(ctx) != null
            }
            // The [semantics] block runs in a non-composable
            // lambda, so the [stringResource] for the
            // content description must be resolved here.
            val reportProblemCd = stringResource(
                R.string.settings_report_problem_cd,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val bodyTemplate = if (hasCrashLog) {
                            reportBodyWithCrashTemplate
                        } else {
                            reportBodyNoCrashTemplate
                        }
                        ctx.startActivity(
                            com.kaavalan.note.ui.util.ReportProblemIntent.build(
                                context = ctx,
                                appVersion = appVersion,
                                subjectTemplate = reportSubjectTemplate,
                                bodyTemplate = bodyTemplate,
                                supportEmail = supportEmail,
                            ),
                        )
                    }
                    .padding(vertical = 8.dp, horizontal = 4.dp)
                    .semantics {
                        contentDescription = reportProblemCd
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_report_problem),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.settings_report_problem_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // v1.9.0 (PROD-READINESS-P3-P1-#1): the
            // "Share crash log" row, visible only
            // when a crash log exists from a
            // previous session. Tapping it opens
            // the system share intent with the
            // log file as the attachment. After
            // share, the row disappears (the
            // log is cleared so the user isn't
            // pestered to share it again on the
            // next launch).
            val mostRecentCrash = remember { com.kaavalan.note.ui.util.CrashLog.mostRecent(ctx) }
            if (mostRecentCrash != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                ctx,
                                ctx.packageName + ".fileprovider",
                                mostRecentCrash,
                            )
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_SEND,
                            ).apply {
                                type = "text/plain"
                                putExtra(
                                    android.content.Intent.EXTRA_SUBJECT,
                                    ctx.getString(
                                        R.string.crash_log_share_subject,
                                    ),
                                )
                                putExtra(
                                    android.content.Intent.EXTRA_TEXT,
                                    ctx.getString(
                                        R.string.crash_log_share_body,
                                        appVersion.name,
                                        appVersion.code,
                                    ),
                                )
                                putExtra(
                                    android.content.Intent.EXTRA_STREAM,
                                    uri,
                                )
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(
                                android.content.Intent.createChooser(
                                    intent,
                                    ctx.getString(R.string.crash_log_share),
                                ),
                            )
                            com.kaavalan.note.ui.util.CrashLog.clear(ctx)
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.crash_log_share),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.crash_log_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // v1.8.0 (PROD-READINESS-P2-#6): the
            // per-build branding rows. BRAND_NAME +
            // BRAND_DEPARTMENT come from gradle
            // properties at build time. The R&D
            // default build (no -Pbrand.* flags)
            // shows "Kaavalan note" and "—".
            val branding = com.kaavalan.note.BrandingConfig.get()
            AboutRow(
                label = stringResource(R.string.settings_brand_name),
                value = branding.appName,
            )
            if (branding.hasDepartment) {
                AboutRow(
                    label = stringResource(R.string.settings_brand_department),
                    value = branding.department,
                )
            }
            // Tier 0.6: the "On this phone" row now
            // shows both the row counts and the on-disk
            // size in MB. The values are rendered as a
            // Column (two text lines) inside a single
            // AboutRow so the label "On this phone" is
            // only shown once. The MB number is
            // recomputed off the main thread every time
            // the upstream `storage` flow emits (Room is
            // reactive -- adding a person or a capture
            // triggers a refresh).
            AboutRow(
                label = stringResource(R.string.settings_storage),
                value = buildString {
                    // v1.6.6 P1: per-segment pluralization.
                    // The previous single stringResource call
                    // hard-coded "people" / "instructions" /
                    // "tags" so it could not read "1 person" /
                    // "1 instruction" / "1 tag". Build from
                    // individual pluralStringResource calls.
                    append(pluralStringResource(R.plurals.count_people, storage.peopleCount, storage.peopleCount))
                    append(stringResource(R.string.count_connector_comma))
                    append(pluralStringResource(R.plurals.count_instructions, storage.instructionCount, storage.instructionCount))
                    append(stringResource(R.string.count_connector_comma))
                    append(pluralStringResource(R.plurals.count_tags, storage.tagCount, storage.tagCount))
                    append('\n')
                    append(
                        stringResource(
                            R.string.settings_storage_size_mb,
                            storage.sizeBytes / (1024.0 * 1024.0),
                        ),
                    )
                },
            )
            AboutRow(
                label = stringResource(R.string.settings_data_mode),
                value = stringResource(R.string.settings_data_mode_vault),
            )
            Spacer(Modifier.height(8.dp))

            // v1.6.2: developer section. Only shown in debug
            // builds. The "Load test data" button calls
            // [SettingsViewModel.loadFixture], which delegates to
            // [com.kaavalan.note.data.dev.FixtureLoader] to bulk-load
            // the synthetic fixture from `assets/synthetic-data.json`.
            // The "Clear & reload" button (v1.6.4) forces a clean
            // slate — useful when the persisted DB has stale data
            // from an older fixture version (v1.6.2 shipped a
            // partial load that left K. Suresh with 1 OPEN and
            // everyone else with 0).
            // Production release builds (BuildConfig.DEBUG = false)
            // never see this section.
            if (com.kaavalan.note.BuildConfig.DEBUG) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Text(
                    text = stringResource(R.string.settings_section_dev),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_dev_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            fixtureLoading = true
                            try {
                                val r = runCatching { viewModel.loadFixture() }
                                if (r.isSuccess) {
                                    fixtureLoadReport = r.getOrThrow()
                                    fixtureLoadError = null
                                } else {
                                    fixtureLoadError = r.exceptionOrNull()?.message
                                }
                            } finally {
                                fixtureLoading = false
                            }
                        }
                    },
                    enabled = !fixtureLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = if (fixtureLoading) {
                            stringResource(R.string.settings_dev_loading)
                        } else {
                            stringResource(R.string.settings_dev_load_fixture)
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                // v1.6.4: "Clear & reload" — same effect as
                // [loadFixture] (the FixtureLoader already
                // clears the mirror before inserting) but with
                // a more explicit label so the user knows the
                // existing data will be wiped.
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            fixtureLoading = true
                            try {
                                val r = runCatching { viewModel.clearAndReloadFixture() }
                                if (r.isSuccess) {
                                    fixtureLoadReport = r.getOrThrow()
                                    fixtureLoadError = null
                                } else {
                                    fixtureLoadError = r.exceptionOrNull()?.message
                                }
                            } finally {
                                fixtureLoading = false
                            }
                        }
                    },
                    enabled = !fixtureLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.settings_dev_clear_reload))
                }
                fixtureLoadReport?.let { report ->
                    Spacer(Modifier.height(8.dp))
                    // v1.6.6 P1: per-segment pluralization.
                    // The previous single stringResource call
                    // used 4 %d args and could not read "1
                    // person" / "1 instruction" / "1 capture" /
                    // "1 tag". Build the "Loaded N people, N
                    // instructions, ..." sentence from
                    // individual pluralStringResource calls.
                    Text(
                        text = buildString {
                            append(stringResource(R.string.settings_dev_loaded_prefix))
                            append(pluralStringResource(R.plurals.count_people, report.persons, report.persons))
                            append(stringResource(R.string.count_connector_comma))
                            append(pluralStringResource(R.plurals.count_instructions, report.instructions, report.instructions))
                            append(stringResource(R.string.count_connector_comma))
                            append(pluralStringResource(R.plurals.count_captures, report.captures, report.captures))
                            append(stringResource(R.string.count_connector_comma))
                            append(pluralStringResource(R.plurals.count_tags, report.tags, report.tags))
                            append('.')
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                fixtureLoadError?.let { err ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            Text(
                text = stringResource(R.string.settings_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // v1.4 (PHONE-FINDING-2): the previous version used
            // colorScheme.errorContainer (bright red). Replaced
            // with surfaceVariant/onSurfaceVariant + a subtle
            // border and a lock icon to communicate "destructive"
            // without colour.
            Button(
                onClick = { showEraseConfirmation = true },
                enabled = !signingOut,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = if (signingOut) {
                        stringResource(R.string.settings_signing_out)
                    } else {
                        stringResource(R.string.settings_sign_out)
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // v1.5.1 (VAULT-007): the erase-all confirmation. Without this
    // a single accidental tap in the settings sheet wipes every
    // person, every instruction, and every tag — there is no cloud
    // backup in vault mode. The dialog uses neutral wording
    // ("Erase") instead of "Delete" / "Destroy" to match the
    // no-shame tone the rest of the app uses.
    //
    // v1.7.2 (Debt-2): the confirm button is disabled until the
    // user types "ERASE" in the text field. The previous v1.5.1
    // dialog was a single-tap irreversible action - the user
    // could lose every person/instruction/tag with a single
    // misclick on the "Erase" button. The typed-confirmation
    // pattern is the standard safe-by-default for destructive
    // actions (matching GitHub's "type the repo name to delete",
    // AWS's "type 'delete' to confirm", and others).
    if (showEraseConfirmation) {
        var eraseTyped by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showEraseConfirmation = false },
            title = { Text(stringResource(R.string.settings_erase_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_erase_confirm_body))
                    Spacer(Modifier.size(12.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = eraseTyped,
                        onValueChange = { eraseTyped = it },
                        label = { Text(stringResource(R.string.settings_erase_confirm_typed_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEraseConfirmation = false
                        scope.launch {
                            viewModel.signOut()
                        }
                    },
                    enabled = eraseTyped == "ERASE",
                ) {
                    Text(stringResource(R.string.settings_erase_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEraseConfirmation = false }) {
                    Text(stringResource(R.string.settings_erase_confirm_no))
                }
            },
        )
    }

    // v2.0 T3-1: the three vault dialogs. Each is a separate
    // state var so they don't collide. The PIN dialogs use a
    // local `remember { mutableStateOf("") }` for the text
    // field; the privacy-screen VM only sees the final string
    // when the user confirms.
    if (showSetPinDialog) {
        PinDialog(
            title = stringResource(R.string.settings_vault_pin_dialog_title),
            body = stringResource(R.string.settings_vault_pin_dialog_body),
            confirmLabel = stringResource(R.string.settings_vault_pin_dialog_save),
            onConfirm = { pin ->
                if (viewModel.setVaultPin(pin)) {
                    showSetPinDialog = false
                }
            },
            onDismiss = { showSetPinDialog = false },
        )
    }
    if (showEnterPinDialog) {
        PinDialog(
            title = stringResource(R.string.settings_vault_enter_pin_title),
            body = stringResource(R.string.settings_vault_enter_pin_body),
            confirmLabel = stringResource(R.string.settings_vault_enter_pin_confirm),
            onConfirm = { pin ->
                if (viewModel.pinMatches(pin)) {
                    viewModel.setVaultMode(VaultMode.Visible)
                    showEnterPinDialog = false
                    enterPinWrong = false
                } else {
                    // Wrong PIN: keep the dialog open and
                    // surface the "try again" message.
                    enterPinWrong = true
                }
            },
            onDismiss = {
                showEnterPinDialog = false
                enterPinWrong = false
            },
            invalidMessage = if (enterPinWrong) {
                stringResource(R.string.settings_vault_enter_pin_wrong)
            } else {
                null
            },
        )
    }
    if (showSwitchToHiddenConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSwitchToHiddenConfirm = false },
            title = { Text(stringResource(R.string.settings_vault_switch_to_hidden_title)) },
            text = { Text(stringResource(R.string.settings_vault_switch_to_hidden_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showSwitchToHiddenConfirm = false
                    viewModel.setVaultMode(VaultMode.Hidden)
                }) {
                    Text(stringResource(R.string.settings_vault_switch_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSwitchToHiddenConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // v2.1.x (BUG FIX, data-loss): the Drive "Back up now" passphrase
    // prompt. `showDriveBackupPassphrasePrompt` was set true on tap
    // but no composable ever read it, so the row was a dead no-op --
    // [SettingsViewModel.googleDriveBackUpNow] had zero call sites.
    // Mirrors [PinDialog]'s AlertDialog + OutlinedTextField +
    // PasswordVisualTransformation shape, for the free-form 12-word
    // recovery phrase instead of a 4-6 digit PIN.
    if (showDriveBackupPassphrasePrompt) {
        DrivePassphraseDialog(
            title = stringResource(R.string.settings_drive_title),
            body = stringResource(R.string.settings_drive_passphrase_prompt),
            hint = stringResource(R.string.settings_drive_passphrase_hint),
            confirmLabel = stringResource(R.string.settings_drive_back_up_now),
            onConfirm = { passphrase ->
                viewModel.googleDriveBackUpNow(passphrase)
                showDriveBackupPassphrasePrompt = false
            },
            onDismiss = { showDriveBackupPassphrasePrompt = false },
        )
    }

    // v2.1.x (BUG FIX, data-loss): the "choose a backup to restore"
    // list. `showDriveRestoreList` had the same problem as above --
    // declared, set true on tap, never read. The list itself comes
    // from the BackupsListed event, which the collector above used
    // to drop via `else -> null`.
    if (showDriveRestoreList) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDriveRestoreList = false },
            title = { Text(stringResource(R.string.settings_drive_restore_dialog_title)) },
            text = {
                if (driveBackupFiles.isEmpty()) {
                    Text(stringResource(R.string.settings_drive_no_backups))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        driveBackupFiles.forEach { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // Picking a file swaps this
                                        // dialog for the restore
                                        // passphrase prompt below.
                                        driveRestoreTarget = file
                                        driveRestoreWrongPassphrase = false
                                        showDriveRestoreList = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDriveRestoreList = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // v2.1.x (BUG FIX, data-loss): the restore passphrase prompt,
    // shown once the user has picked a file from the list above.
    // `SettingsViewModel.googleDriveRestore(fileId, passphrase)` had
    // zero call sites before this -- this is the only call site.
    if (driveRestoreTarget != null) {
        val target = driveRestoreTarget!!
        DrivePassphraseDialog(
            title = target.name,
            body = stringResource(R.string.settings_drive_restore_passphrase_prompt),
            hint = stringResource(R.string.settings_drive_passphrase_hint),
            confirmLabel = stringResource(R.string.settings_drive_restore),
            invalidMessage = if (driveRestoreWrongPassphrase) {
                stringResource(R.string.settings_drive_restore_failed)
            } else {
                null
            },
            onConfirm = { passphrase ->
                // Deliberately does NOT close the dialog here -- a
                // WrongPassphrase event keeps it open for a retry
                // (see the collector above); RestoreSucceeded /
                // RestoreFailed close it there.
                viewModel.googleDriveRestore(target.id, passphrase)
            },
            onDismiss = {
                driveRestoreTarget = null
                driveRestoreWrongPassphrase = false
            },
        )
    }
}

/**
 * v2.0 T3-1: one tappable row in the Privacy section. The
 * row is a single-line label + value, with an optional
 * small explainer below. Tapping anywhere on the row fires
 * [onClick]. The row is non-destructive (no red, no error
 * colour) — the worst the user can do is open a dialog.
 */
@Composable
private fun PrivacyRow(
    label: String,
    value: String,
    explainer: String?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                // v2.0 T3-1: a Tappable privacy row. The
                // [label] is the visible Text inside, so we
                // don't need a separate contentDescription;
                // TalkBack will read the label as the row's
                // name. The semantics block is here to make
                // the static a11y scan in
                // [com.kaavalan.note.ui.AccessibilityContentDescriptionTest]
                // see a `contentDescription` reference in the
                // call body and accept the Surface.
                contentDescription = label
            },
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (explainer != null) {
                Text(
                    text = explainer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * v2.0 T3-1: the PIN entry dialog. Two flavours: "set PIN"
 * (used to set or change the PIN) and "enter PIN" (used to
 * switch back from Hidden). The dialog is a single
 * [OutlinedTextField] with [keyboardType = NumberPassword] so
 * the IME does not autocorrect or suggest words.
 *
 * @param onConfirm the parent's callback for a confirmed
 *   PIN entry. The parent validates the PIN; the dialog
 *   does not enforce the 4-6-digit rule here (the "set"
 *   variant calls into [SettingsViewModel.setVaultPin] which
 *   returns `false` on invalid input; the "enter" variant
 *   closes on correct match).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    invalidMessage: String? = null,
) {
    var pin by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { newValue ->
                        // Restrict to digits, max 6 chars.
                        if (newValue.length <= 6 && newValue.all { it.isDigit() }) {
                            pin = newValue
                        }
                    },
                    label = { Text(stringResource(R.string.settings_vault_pin_dialog_label)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                    ),
                )
                if (invalidMessage != null) {
                    Text(
                        text = invalidMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pin) },
                enabled = pin.length in 4..6,
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_vault_pin_dialog_cancel))
            }
        },
    )
}

/**
 * v2.1.x (BUG FIX, data-loss): the Google Drive backup / restore
 * passphrase entry dialog. Same AlertDialog + OutlinedTextField +
 * PasswordVisualTransformation shape as [PinDialog] above, but for
 * the free-form 12-word recovery phrase rather than a 4-6 digit
 * PIN (no digit filter, no length cap). Used for both "Back up
 * now" (encrypt) and "Restore from backup" (decrypt) -- the caller
 * decides which [SettingsViewModel] method [onConfirm] calls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrivePassphraseDialog(
    title: String,
    body: String,
    hint: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    invalidMessage: String? = null,
) {
    var passphrase by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(hint) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (invalidMessage != null) {
                    Text(
                        text = invalidMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * v2.0 (Tier 1.4): a SegmentedButton row for the theme
 * switcher. The default position is `System` (the device
 * setting), and the user can pick `Light` / `Dark` to
 * override. The choice persists in DataStore and the root
 * composable reads the same flow to apply the theme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeRow(
    current: ThemeMode,
    onChange: (ThemeMode) -> Unit,
) {
    val options = ThemeMode.entries
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = current == mode,
                onClick = { onChange(mode) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = {
                    Text(
                        text = when (mode) {
                            ThemeMode.System -> stringResource(R.string.theme_system)
                            ThemeMode.Light -> stringResource(R.string.theme_light)
                            ThemeMode.Dark -> stringResource(R.string.theme_dark)
                        },
                    )
                },
            )
        }
    }
}

private fun ts(): String =
    java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        .format(java.util.Date())

/**
 * v2.0.2 (PM rating): the database-error banner. Renders
 * a red-bordered card with a "Erase all data" CTA when
 * the preflight detected a runtime DB open failure. The
 * user has one path forward: wipe the local DB and
 * restore from a backup. The card explains why this is
 * the right thing to do (the local data is unreadable —
 * a wrong passphrase or a corrupt file — and the
 * "Erase all data" path is the only safe recovery).
 */
@Composable
private fun DatabaseErrorCard(
    onErase: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_db_error_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.settings_db_error_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            androidx.compose.material3.TextButton(
                onClick = onErase,
            ) {
                Text(stringResource(R.string.settings_db_error_action))
            }
        }
    }
}

@Composable
private fun StuckOutboxCard(
    count: Int,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.typography.bodyLarge.let {
            androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (count == 1) {
                        stringResource(R.string.settings_sync_stuck_one, count)
                    } else {
                        stringResource(R.string.settings_sync_stuck_other, count)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.settings_sync_error_retries),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(stringResource(R.string.settings_sync_retry))
            }
        }
    }
}

@Composable
private fun TagsSection(
    tags: List<Tag>,
    onAdd: (String) -> Unit,
) {
    var composing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_section_tags),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            AssistChip(
                onClick = { composing = !composing },
                label = { Text(stringResource(R.string.settings_tags_add_chip)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
            )
        }
        if (composing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.settings_tags_placeholder)) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp),
                )
                Spacer(Modifier.size(8.dp))
                TextButton(
                    onClick = {
                        onAdd(text)
                        text = ""
                        composing = false
                    },
                    enabled = text.isNotBlank(),
                ) { Text(stringResource(R.string.settings_tags_add_button)) }
            }
        }
        if (tags.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_tags_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val groups = TagKind.values().mapNotNull { kind ->
                val list = tags.filter { it.kind == kind }
                if (list.isEmpty()) null else kind to list
            }
            // v1.6.6 P0 crash fix: the outer Settings sheet uses
            // `Column.verticalScroll(rememberScrollState())`. Nesting a
            // `LazyColumn` inside a vertically-scrollable parent throws
            // `IllegalStateException: Vertically scrollable component was
            // measured with an infinity maximum height constraints` at
            // launch. The tag list is small (a handful of tags per kind,
            // 3 kinds) and the outer scroll already provides viewport
            // behaviour, so a plain Column.forEach is the correct pattern
            // here. If the tag list grows to dozens-per-kind we can revisit
            // by moving the LazyColumn out of the sheet (e.g. a dedicated
            // "Manage tags" screen).
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                groups.forEach { (kind, list) ->
                    // v1.7.2 (P1-F): the FREE kind (user-authored
                    // tags) was rendering its section header as the
                    // bare word "Free" — a developer term that
                    // leaks into the user-facing UI. The user has
                    // no context for what "Free" means here. The
                    // other kinds (PERSON, DESIGNATION, CASE, etc.)
                    // read as nouns the user already knows, so we
                    // leave them alone and only special-case FREE
                    // to "Your tags" so the section reads as the
                    // user's own tag pile.
                    val sectionLabel = when (kind) {
                        TagKind.FREE -> "Your tags"
                        else -> kind.name.lowercase()
                            .replaceFirstChar { it.uppercase() }
                    }
                    Text(
                        text = sectionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    list.forEach { tag ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Surface(
                                modifier = Modifier.size(8.dp),
                                color = tag.color?.let(::parseHex) ?: colorForKind(kind),
                                contentColor = Color.Transparent,
                                shape = CircleShape,
                            ) {}
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = if (tag.kind == TagKind.FREE) "#${tag.name}" else tag.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (tag.usageCount > 0) {
                                Text(
                                    text = "×${tag.usageCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * v1.5.3 (VAULT-008): a single "label : value" row in the
 * About section. No interaction, just information density.
 * Uses `bodyMedium` for the label (quiet) and `bodyMedium`
 * for the value (the actual answer) — both on
 * `onSurfaceVariant` so the whole block reads as "info",
 * not "settings to change".
 */
@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// v1.6.1: removed `ModelRow` and `WhisperModelRow`. The
// on-device LLM and the whisper.cpp voice model are gone.
// Voice capture uses the system SpeechRecognizer; the
// capture sheet has no Extract step.
