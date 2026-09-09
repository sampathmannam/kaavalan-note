package com.kaavalan.note.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kaavalan.note.R

/**
 * Bottom sheet to capture a new [com.kaavalan.note.data.person.Person].
 * Three fields, all optional except Name. Save is disabled until Name
 * is non-blank — keeps the form ADHD-friendly (one obvious action).
 *
 * v1.5.3 fixes:
 *  - VAULT-002: Back key first hides the soft keyboard (if it's
 *    up), then closes the sheet. The previous behaviour was to
 *    skip the IME-close step and dismiss the whole sheet, which
 *    silently lost any typed data.
 *  - VAULT-004: Name / Designation / Station use
 *    `capitalization = Words` and `autoCorrect = false` so the
 *    soft keyboard doesn't insert stray characters into proper
 *    nouns (e.g. "Thanjavur" -> "Thanjavurv").
 *
 * v2.1.2 (P1-#3) focus-trap fix:
 *  - The v1.5.3 layout relied on `OutlinedTextField`'s default
 *    tap-to-focus for moving between fields. On the 1264x2780
 *    Android 14 device used for QA, the first tap on a sibling
 *    field (Designation or Station) while the IME was up was
 *    being swallowed by the sheet's touch-dispatch layer, and
 *    the cursor stayed in Name. All subsequent text landed in
 *    Name, trapping the user in the first field.
 *  - The fix wires each field to a stable `FocusRequester` and
 *    uses `KeyboardActions.onNext` to move focus on the IME's
 *    Next button — a deterministic path that doesn't depend on
 *    the sheet's touch dispatch. With `imeAction = ImeAction.Next`
 *    on Name → Designation, and `ImeAction.Next` on Designation
 *    → Station, the IME's Next arrow deterministically walks
 *    through the form. The keyboard's Done arrow on Station
 *    dismisses the IME.
 *  - IME visibility for the VAULT-002 back-handler is now read
 *    from `WindowInsets.ime` rather than per-field focus
 *    listeners, so the focus listeners no longer recompose the
 *    sheet on every focus change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPersonSheet(
    onSave: (name: String, designation: String?, station: String?) -> Unit,
    onDismiss: () -> Unit,
    isSaving: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(
        // v1.9.7 (UX-001): skip the partial-expanded state so the
        // sheet goes straight to fully expanded when the IME is
        // up. Without this, the sheet sits at ~50% height with
        // Save below the keyboard on 480 dpi Android 14+ devices.
        skipPartiallyExpanded = true,
    )
    var name by rememberSaveable { mutableStateOf("") }
    var designation by rememberSaveable { mutableStateOf("") }
    var station by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    // v2.1.2 (P1-#3): stable focus targets. The IME's Next
    // action on each field calls the next field's
    // `requestFocus()`, so tapping the Next arrow on the
    // soft keyboard walks the form deterministically. Tap-to-
    // focus is the secondary path; the explicit requesters
    // here mean a missed tap can be retried without the
    // cursor getting stuck in the previous field.
    val nameFocusRequester = remember { FocusRequester() }
    val designationFocusRequester = remember { FocusRequester() }
    val stationFocusRequester = remember { FocusRequester() }

    // VAULT-002: back press hides the soft keyboard first, then
    // closes the sheet. Read IME visibility from `WindowInsets.ime`
    // so we don't have to recompose the sheet on every focus
    // change (the v1.5.3 focus-listener approach).
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    BackHandler(enabled = imeVisible) {
        keyboard?.hide()
    }
    BackHandler(enabled = !imeVisible) {
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        com.kaavalan.note.ui.theme.DialogSystemBars()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.home_add_person),
                style = MaterialTheme.typography.titleLarge,
            )
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // v2.1.2 (P1-#3): each TextField owns a
            // `focusRequester` and a `KeyboardActions` onNext
            // hook so the keyboard's Next button deterministically
            // moves focus to the next field. Without the explicit
            // focus requesters the first tap on a sibling
            // TextField after the Name field was lost — the
            // cursor stayed in Name and all subsequent text
            // landed in Name. See the file-level docstring for
            // the v1.5.3 → v2.1.2 history.
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.person_name)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(nameFocusRequester),
                // VAULT-004: Words capitalization (proper nouns),
                // autocorrect off.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { designationFocusRequester.requestFocus() },
                ),
            )
            OutlinedTextField(
                value = designation,
                onValueChange = { designation = it },
                label = { Text(stringResource(R.string.person_designation)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(designationFocusRequester),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { stationFocusRequester.requestFocus() },
                ),
            )
            OutlinedTextField(
                value = station,
                onValueChange = { station = it },
                label = { Text(stringResource(R.string.person_station)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(stationFocusRequester),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { keyboard?.hide() },
                ),
            )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    onSave(
                        name.trim(),
                        designation.trim().ifEmpty { null },
                        station.trim().ifEmpty { null },
                    )
                },
                enabled = name.isNotBlank() && !isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isSaving) "Saving…" else "Save contact")
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
