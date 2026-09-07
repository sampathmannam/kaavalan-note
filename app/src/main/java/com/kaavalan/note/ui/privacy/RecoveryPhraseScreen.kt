package com.kaavalan.note.ui.privacy

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kaavalan.note.R
import com.kaavalan.note.ui.settings.SettingsViewModel

/**
 * v2.0 T3-2 (recovery phrase): the 3-step flow for generating
 * a fresh 12-word BIP39 recovery phrase.
 *
 *  - Step 1 (`Display.writtenDownAcknowledged = false`): show
 *    the 12 words in a 3x4 grid with copy-to-clipboard. Tell
 *    the user to write them down.
 *  - Step 2 (`Display.writtenDownAcknowledged = true`):
 *    re-render the words in a shuffled order; the user taps
 *    them in the original order. Wrong order -> error
 *    message, retry from empty.
 *  - Step 3 (`Confirmed`): the hash is persisted; show a
 *    "Done" message and a back button.
 *
 * The whole screen is FLAG_SECUREd (no screenshots, no
 * recents thumbnail) — see [FlagSecureEffect].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryPhraseScreen(
    onClose: () -> Unit,
    viewModel: RecoveryPhraseViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    // v2.0 T3-2: Hilt does not allow ViewModel-into-
    // ViewModel injection. We get the Settings VM here and
    // wire its `onRecoveryPhraseChanged()` as the callback
    // the recovery VM calls when the phrase hash commits.
    LaunchedEffect(Unit) {
        viewModel.onPhraseHashChanged = settingsViewModel::onRecoveryPhraseChanged
    }
    FlagSecureEffect()
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) {
        if (state is RecoveryPhraseState.Idle) {
            viewModel.start()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recovery_phrase_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.onDismissed()
                        onClose()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.recovery_phrase_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            RecoveryPhraseState.Idle -> {
                // Brief blank state between dismiss and the next
                // start; nothing to render.
            }
            RecoveryPhraseState.ConfirmRegenerate -> {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {
                        viewModel.cancelRegenerate()
                        onClose()
                    },
                    title = { Text(stringResource(R.string.recovery_phrase_regenerate_confirm_title)) },
                    text = { Text(stringResource(R.string.recovery_phrase_regenerate_confirm_body)) },
                    confirmButton = {
                        TextButton(onClick = viewModel::confirmRegenerate) {
                            Text(stringResource(R.string.recovery_phrase_regenerate_confirm_action))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            viewModel.cancelRegenerate()
                            onClose()
                        }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            }
            is RecoveryPhraseState.Display -> {
                DisplayStep(
                    state = s,
                    onWrittenDown = viewModel::onWrittenDown,
                    onPickWord = viewModel::pickWord,
                    onRetry = viewModel::retryVerify,
                    paddingValues = padding,
                )
            }
            RecoveryPhraseState.Confirmed -> {
                ConfirmedStep(onClose = {
                    viewModel.onDismissed()
                    onClose()
                }, paddingValues = padding)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DisplayStep(
    state: RecoveryPhraseState.Display,
    onWrittenDown: () -> Unit,
    onPickWord: (String) -> Unit,
    onRetry: () -> Unit,
    paddingValues: androidx.compose.foundation.layout.PaddingValues,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clipboardLabel = stringResource(R.string.recovery_clipboard_label)
    // v2.1.1 (security): clear the clipboard when the
    // user leaves the recovery-phrase screen, so the
    // 12 words don't sit in the system clipboard
    // indefinitely. The ClipData the [setPrimaryClip]
    // call below wrote has a known label
    // (R.string.recovery_clipboard_label) and the same package
    // (this app), so we can identify + clear it
    // safely without wiping the user's other
    // clipboard content.
    DisposableEffect(Unit) {
        onDispose {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val current = cm.primaryClip
            if (current != null &&
                current.description?.label == clipboardLabel
            ) {
                // Android 13+ supports clearPrimaryClip;
                // on older versions the best we can do is
                // overwrite with an empty ClipData.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runCatching { cm.clearPrimaryClip() }
                } else {
                    cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.recovery_phrase_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Step 1: the 12 words in a 3x4 grid.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.recovery_phrase_step_1),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                state.phrase.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEachIndexed { col, word ->
                            val index = state.phrase.indexOf(word) + 1
                            Text(
                                text = "$index. $word",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Pad the row if it has fewer than 3 items
                        // (12 / 3 = 4 full rows, so this never
                        // fires; defensive only).
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        // v2.1.1 (security): the recovery
                        // phrase is sensitive PII (12 words
                        // grant full account restore). v2.1.0
                        // used the Compose [LocalClipboardManager]
                        // which doesn't support
                        // [ClipDescription.EXTRA_ISENSITIVE]
                        // — the system clipboard shows the
                        // words in a toast preview. v2.1.1
                        // switches to the platform
                        // [ClipboardManager] with the
                        // IS_SENSITIVE flag (Android 13+) +
                        // auto-clears the clipboard after 60s
                        // (the DisposableEffect below) so the
                        // phrase isn't left in the system
                        // clipboard indefinitely.
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val phrase = state.phrase.joinToString(" ")
                        val clip = ClipData.newPlainText(clipboardLabel, phrase)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val extras = PersistableBundle().apply {
                                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                            }
                            clip.description.extras = extras
                        }
                        cm.setPrimaryClip(clip)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.recovery_phrase_copy))
                }
                // v1.8.0 (PROD-READINESS-P1-#2): the
                // "Save as PDF" row. Generates an A4
                // recovery sheet in cacheDir and hands
                // it to the system share sheet. The
                // user can save to Google Drive, print,
                // or AirDrop. Lives next to the existing
                // "Copy" button so the two export
                // options are co-located.
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val file = com.kaavalan.note.ui.privacy.RecoveryPdfGenerator.generate(
                            context = ctx,
                            phraseWords = state.phrase,
                        )
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            ctx,
                            ctx.packageName + ".fileprovider",
                            file,
                        )
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(
                            android.content.Intent.createChooser(send, "Save recovery sheet").apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.recovery_phrase_save_pdf))
                }
            }
        }

        if (!state.writtenDownAcknowledged) {
            Button(
                onClick = onWrittenDown,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.recovery_phrase_written_down))
            }
        } else {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = stringResource(R.string.recovery_phrase_step_2),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.recovery_phrase_verify_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Step 2: the shuffled words as chips. Already-picked
            // words are rendered dim + non-clickable.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                state.shuffled.forEach { word ->
                    val picked = word in state.picked
                    AssistChip(
                        onClick = { if (!picked) onPickWord(word) },
                        label = { Text(word) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (picked) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                    )
                }
            }
            if (state.errorMessage != null) {
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(stringResource(R.string.recovery_phrase_retry))
                }
            } else {
                Text(
                    text = stringResource(
                        R.string.recovery_phrase_picked_count,
                        state.picked.size,
                        state.phrase.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmedStep(
    onClose: () -> Unit,
    paddingValues: androidx.compose.foundation.layout.PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp),
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.recovery_phrase_confirmed_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Text(
            text = stringResource(R.string.recovery_phrase_confirmed_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.recovery_phrase_done))
        }
    }
}
