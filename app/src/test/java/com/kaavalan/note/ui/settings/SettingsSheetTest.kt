package com.kaavalan.note.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.4 (PHONE-FINDING-2) + BUG-AUDIT-2: the erase-all-data button
 * must not use the red `errorContainer` colour.
 *
 * v1.5.1: the button no longer calls `viewModel.signOut()`
 * directly — it now opens a confirmation dialog first (VAULT-007).
 * The test finds the button by the destructive trigger
 * `showEraseConfirmation = true` instead of the old direct call.
 *
 * v2.1.x (BUG FIX regression guard, adversarial QA audit, data-loss):
 * pins the fix for the Google Drive backup/restore dead-no-op bug.
 * `showDriveBackupPassphrasePrompt` / `showDriveRestoreList` were
 * declared via `remember { mutableStateOf(false) }` and set `true`
 * on tap, but no composable ever read them, so no dialog rendered
 * and `SettingsViewModel.googleDriveBackUpNow` /
 * `.googleDriveRestore` had zero call sites — "Back up now" and
 * "Restore from backup" silently did nothing when signed in. The
 * `driveBackupEvent` collector's `when` also ended in a bare
 * `else -> null`, dropping `BackupsListed` / `SignInRequired` /
 * `PassphraseRequired` on the floor. Fixing this the naive way (add
 * the dialogs, wire the calls, remove the `else`) introduced a
 * second, genuinely-non-compiling bug: the new dialog state is
 * assigned from inside the `driveBackupEvent` `LaunchedEffect`
 * (`showDriveBackupPassphrasePrompt = false`, etc.), and that
 * `LaunchedEffect` sits *before* the `var ... by remember` lines in
 * the function body. Kotlin resolves a local `var` by its *textual*
 * declaration position even when referenced inside a lambda defined
 * earlier in the same function — this failed with "Unresolved
 * reference" under `:app:compileDebugKotlin` until the declarations
 * were moved above the `LaunchedEffect`. Same rationale as
 * [com.kaavalan.note.features.capture.NoteBarTest] for why this is a
 * static source scan rather than a driven Compose click: see
 * [com.kaavalan.note.ui.home.HomeScreenTest].
 */
class SettingsSheetTest {

    private val settingsSheetFile: File =
        File("src/main/java/com/kaavalan/note/ui/settings/SettingsSheet.kt").absoluteFile

    private fun source(): String = settingsSheetFile.readText(Charsets.UTF_8)

    private fun signOutButtonCallSite(text: String): String? {
        val regex = Regex("""\bButton\s*\(""")
        regex.findAll(text).forEach { m ->
            val (body, _) = extractCallAndTrailingLambda(text, m.range.last) ?: return@forEach
            // v1.5.1: the destructive button either calls
            // viewModel.signOut() directly (legacy v1.4 shape) OR
            // sets showEraseConfirmation = true (v1.5.1 shape with
            // a confirmation dialog). Match either.
            if (body.contains("viewModel.signOut") || body.contains("showEraseConfirmation")) {
                return body
            }
        }
        return null
    }

    /**
     * BUG-AUDIT-2: the Sign out button is not red.
     */
    @Test
    fun `BUG-AUDIT-2 sign out button is not red`() {
        val text = source()
        val body = signOutButtonCallSite(text)
        assertTrue(
            "SettingsSheet.kt must contain a Button(...) that calls viewModel.signOut().",
            body != null,
        )

        assertFalse(
            "BUG-AUDIT-2: the Sign out button must NOT use errorContainer. Found: $body",
            body!!.contains("errorContainer"),
        )
        assertFalse(
            "BUG-AUDIT-2: the Sign out button must NOT use onErrorContainer. Found: $body",
            body.contains("onErrorContainer"),
        )
        assertFalse(
            "BUG-AUDIT-2: the Sign out button must NOT use colorScheme.error. Found: $body",
            body.contains("colorScheme.error"),
        )

        assertTrue(
            "BUG-AUDIT-2: the Sign out button must use surfaceVariant. Found: $body",
            body.contains("surfaceVariant"),
        )
        assertTrue(
            "BUG-AUDIT-2: the Sign out button must use onSurfaceVariant. Found: $body",
            body.contains("onSurfaceVariant"),
        )
    }

    @Test
    fun `sign out button has a visual differentiator beyond colour - either border or icon prefix`() {
        val text = source()
        val body = signOutButtonCallSite(text)
        assertTrue("Sign out button must exist", body != null)
        val hasBorder = body!!.contains("BorderStroke") || body.contains("border =")
        val hasIconPrefix = body.contains("Icons.Default.Lock") ||
            body.contains("Icons.Outlined.Lock") ||
            body.contains("Icons.Filled.Lock")
        assertTrue(
            "BUG-AUDIT-2: the Sign out button must carry a non-colour " +
                "differentiator (border or icon prefix). Found: $body",
            hasBorder || hasIconPrefix,
        )
    }

    @Test
    fun `sign out button label resolves from a string resource (a11y)`() {
        val text = source()
        val body = signOutButtonCallSite(text)
        assertTrue("Sign out button must exist", body != null)
        assertTrue(
            "BUG-AUDIT-2: the Sign out button's text must come from " +
                "stringResource. Found: $body",
            body!!.contains("stringResource"),
        )
    }

    @Test
    fun `SettingsSheet kt source file is at the expected path`() {
        assertTrue(
            "SettingsSheet.kt must exist at ${settingsSheetFile.absolutePath}.",
            settingsSheetFile.exists(),
        )
    }

    /**
     * v2.1.x (BUG FIX, data-loss): `showDriveBackupPassphrasePrompt`
     * being merely declared and set was exactly how the original bug
     * hid — this asserts it is actually *read* by a block that
     * renders a dialog and drives the backup call, not just toggled.
     */
    @Test
    fun `showDriveBackupPassphrasePrompt is read by an if-block that renders a dialog calling googleDriveBackUpNow`() {
        val text = source()
        assertTrue(
            "SettingsSheet.kt must declare showDriveBackupPassphrasePrompt as remembered state.",
            text.contains("var showDriveBackupPassphrasePrompt by remember"),
        )
        val block = extractIfBlock(text, "if (showDriveBackupPassphrasePrompt)")
        assertTrue(
            "showDriveBackupPassphrasePrompt must be read by an `if (showDriveBackupPassphrasePrompt)` " +
                "block that renders a dialog. Before the fix the flag was set true on tap but never " +
                "read anywhere, so 'Back up now' was a dead no-op.",
            block != null,
        )
        assertTrue(
            "the showDriveBackupPassphrasePrompt block must call googleDriveBackUpNow(...), the " +
                "SettingsViewModel method that previously had zero call sites. Found:\n$block",
            block!!.contains("googleDriveBackUpNow("),
        )
    }

    /**
     * v2.1.x (BUG FIX, data-loss): same dead-flag shape as
     * `showDriveBackupPassphrasePrompt`, for the restore list.
     */
    @Test
    fun `showDriveRestoreList is read by an if-block that renders the Drive backup list`() {
        val text = source()
        assertTrue(
            "SettingsSheet.kt must declare showDriveRestoreList as remembered state.",
            text.contains("var showDriveRestoreList by remember"),
        )
        val block = extractIfBlock(text, "if (showDriveRestoreList)")
        assertTrue(
            "showDriveRestoreList must be read by an `if (showDriveRestoreList)` block that " +
                "renders the list of Drive backups -- before the fix this flag was set true on " +
                "tap but never read, so 'Restore from backup' was a dead no-op.",
            block != null,
        )
        assertTrue(
            "the showDriveRestoreList block must render driveBackupFiles and let the user pick " +
                "one (setting driveRestoreTarget). Found:\n$block",
            block!!.contains("driveBackupFiles") && block.contains("driveRestoreTarget ="),
        )
    }

    /**
     * v2.1.x (BUG FIX, data-loss): the actual `googleDriveRestore(fileId, passphrase)`
     * call site -- this had zero call sites anywhere in the app before the fix.
     */
    @Test
    fun `googleDriveRestore has a call site guarded by the picked restore target`() {
        val text = source()
        val block = extractIfBlock(text, "if (driveRestoreTarget != null)")
        assertTrue(
            "driveRestoreTarget must be read by an `if (driveRestoreTarget != null)` block " +
                "that prompts for the restore passphrase and calls googleDriveRestore(...).",
            block != null,
        )
        assertTrue(
            "the driveRestoreTarget block must call googleDriveRestore(...), which previously " +
                "had zero call sites anywhere in the app. Found:\n$block",
            block!!.contains("googleDriveRestore("),
        )
    }

    /**
     * v2.1.x (BUG FIX, data-loss): the `driveBackupEvent` collector's
     * `when (event)` used to end in `else -> null`, silently dropping
     * `BackupsListed` / `SignInRequired` / `PassphraseRequired`. This
     * asserts every `DriveBackupEvent` subtype is branched on
     * explicitly and there is no catch-all `else` to hide a future
     * dropped event the same way.
     */
    @Test
    fun `driveBackupEvent collector handles every DriveBackupEvent subtype with no catch-all else`() {
        val text = source()
        val collectIdx = text.indexOf("viewModel.driveBackupEvent.collect")
        assertTrue("the driveBackupEvent collector must exist", collectIdx >= 0)
        val whenIdx = text.indexOf("when (event)", collectIdx)
        assertTrue("the collector must branch on the event via when (event)", whenIdx >= 0)
        val whenBraceOpen = text.indexOf('{', whenIdx)
        assertTrue(whenBraceOpen >= 0)
        val whenBlock = extractBraceBlockFrom(text, whenBraceOpen)
        assertTrue("could not extract the when(event) block body", whenBlock != null)

        assertFalse(
            "v2.1.x regression: an `else ->` branch here previously swallowed BackupsListed / " +
                "SignInRequired / PassphraseRequired silently. The when must branch explicitly " +
                "on every DriveBackupEvent subtype instead of falling back to a catch-all. " +
                "Found:\n$whenBlock",
            Regex("""\belse\s*->""").containsMatchIn(whenBlock!!),
        )
        listOf(
            "BackUpSuccess",
            "BackUpFailed",
            "RestoreSucceeded",
            "RestoreFailed",
            "WrongPassphrase",
            "BackupsListed",
            "SignInRequired",
            "PassphraseRequired",
        ).forEach { eventType ->
            assertTrue(
                "when (event) must handle DriveBackupEvent.$eventType explicitly. Found:\n$whenBlock",
                whenBlock.contains("DriveBackupEvent.$eventType"),
            )
        }
    }

    /**
     * v2.1.x (BUG FIX, compile-order regression): fixing the dead-flag
     * bug above the naive way -- assigning into the new dialog state
     * from inside the `driveBackupEvent` `LaunchedEffect` -- does not
     * compile unless that state is declared *before* the
     * `LaunchedEffect` in the function body. Kotlin resolves a local
     * `var` reference by textual declaration position even inside a
     * lambda defined earlier in the same function, so declaring these
     * vars after the collector fails `:app:compileDebugKotlin` with
     * "Unresolved reference" (verified while root-causing this fix).
     */
    @Test
    fun `Drive backup dialog state is declared before the LaunchedEffect that assigns it`() {
        val text = source()
        val collectIdx = text.indexOf("viewModel.driveBackupEvent.collect")
        assertTrue("the driveBackupEvent collector must exist", collectIdx >= 0)

        listOf(
            "showDriveBackupPassphrasePrompt",
            "showDriveRestoreList",
            "driveBackupFiles",
            "driveRestoreTarget",
            "driveRestoreWrongPassphrase",
        ).forEach { name ->
            val declIdx = text.indexOf("var $name by remember")
            assertTrue("$name must be declared via `var $name by remember`", declIdx >= 0)
            assertTrue(
                "$name (declared at index $declIdx) must be declared BEFORE the " +
                    "driveBackupEvent LaunchedEffect (at index $collectIdx) that assigns into " +
                    "it, or SettingsSheet.kt fails to compile with 'Unresolved reference'.",
                declIdx < collectIdx,
            )
        }
    }

    /**
     * v2.x (BUG FIX regression guard, adversarial QA audit, Settings >
     * Developer section): the "Load test data" / "Clear & reload"
     * buttons declared `fixtureLoading` and read it via
     * `enabled = !fixtureLoading`, but the `onClick` lambdas never set
     * it -- `scope.launch { val r = viewModel.loadFixture(); ... }`
     * called straight through with no `fixtureLoading = true/false`
     * around the suspend call. The disabled-while-loading guard was
     * therefore always evaluating `!false`, i.e. always enabled, so a
     * fast double-tap could fire two concurrent fixture loads.
     * Separately, `fixtureLoadError` was declared and rendered
     * (`fixtureLoadError?.let { ... }`) but never assigned anywhere --
     * `loadFixture()` / `clearAndReloadFixture()` return the
     * [com.kaavalan.note.data.dev.FixtureLoader.LoadReport] directly
     * (not a `Result`), so the naive `val r = viewModel.loadFixture()`
     * call site had no failure branch to assign it from, making the
     * error-text UI path unreachable dead code.
     *
     * Finds the `Button(...)`/`OutlinedButton(...)` call site whose
     * body contains [marker] (e.g. `"viewModel.loadFixture()"`),
     * mirroring [signOutButtonCallSite] but for either button type.
     */
    private fun fixtureButtonCallSite(text: String, marker: String): String? {
        val regex = Regex("""\b(OutlinedButton|Button)\s*\(""")
        regex.findAll(text).forEach { m ->
            val (body, _) = extractCallAndTrailingLambda(text, m.range.last) ?: return@forEach
            if (body.contains(marker)) return body
        }
        return null
    }

    @Test
    fun `Load test data button sets fixtureLoading true and false around the suspend call`() {
        val text = source()
        val body = fixtureButtonCallSite(text, "viewModel.loadFixture()")
        assertTrue(
            "SettingsSheet.kt must contain a Button(...) that calls viewModel.loadFixture().",
            body != null,
        )
        assertTrue(
            "BUG FIX regression: the Load test data button must set fixtureLoading = true " +
                "before calling loadFixture(), otherwise `enabled = !fixtureLoading` never " +
                "actually disables the button while the load is in flight and a fast " +
                "double-tap fires two concurrent fixture loads. Found:\n$body",
            Regex("""fixtureLoading\s*=\s*true""").containsMatchIn(body!!),
        )
        assertTrue(
            "the Load test data button must also set fixtureLoading = false once the call " +
                "completes, so the button re-enables afterward. Found:\n$body",
            Regex("""fixtureLoading\s*=\s*false""").containsMatchIn(body),
        )
    }

    @Test
    fun `Clear & reload button sets fixtureLoading true and false around the suspend call`() {
        val text = source()
        val body = fixtureButtonCallSite(text, "viewModel.clearAndReloadFixture()")
        assertTrue(
            "SettingsSheet.kt must contain a button that calls viewModel.clearAndReloadFixture().",
            body != null,
        )
        assertTrue(
            "BUG FIX regression: same dead-guard bug as Load test data -- Clear & reload must " +
                "set fixtureLoading = true before calling clearAndReloadFixture(). Found:\n$body",
            Regex("""fixtureLoading\s*=\s*true""").containsMatchIn(body!!),
        )
        assertTrue(
            "Clear & reload must also set fixtureLoading = false once the call completes. " +
                "Found:\n$body",
            Regex("""fixtureLoading\s*=\s*false""").containsMatchIn(body),
        )
    }

    @Test
    fun `fixtureLoadError is actually assigned by the fixture-load click handlers`() {
        val text = source()
        val loadBody = fixtureButtonCallSite(text, "viewModel.loadFixture()")
        val reloadBody = fixtureButtonCallSite(text, "viewModel.clearAndReloadFixture()")
        assertTrue("Load test data button must exist", loadBody != null)
        assertTrue("Clear & reload button must exist", reloadBody != null)

        assertTrue(
            "BUG FIX regression: fixtureLoadError is declared and rendered " +
                "(`fixtureLoadError?.let { ... }`) in the Developer section but was never " +
                "assigned by the Load test data click handler -- loadFixture() returns the " +
                "LoadReport directly (not a Result), so without an explicit failure branch " +
                "the error-text UI path was unreachable dead code. Found:\n$loadBody",
            Regex("""fixtureLoadError\s*=""").containsMatchIn(loadBody!!),
        )
        assertTrue(
            "same dead-assignment bug for Clear & reload. Found:\n$reloadBody",
            Regex("""fixtureLoadError\s*=""").containsMatchIn(reloadBody!!),
        )
    }

    private fun extractIfBlock(text: String, marker: String): String? {
        val idx = text.indexOf(marker)
        if (idx < 0) return null
        val braceOpen = text.indexOf('{', idx)
        if (braceOpen < 0) return null
        val block = extractBraceBlockFrom(text, braceOpen) ?: return null
        val blockEnd = braceOpen + block.length
        return text.substring(idx, blockEnd)
    }

    private fun extractBraceBlockFrom(text: String, openBraceIndex: Int): String? {
        if (openBraceIndex >= text.length || text[openBraceIndex] != '{') return null
        var depth = 0
        var i = openBraceIndex
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(openBraceIndex, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun extractCallAndTrailingLambda(text: String, openIndex: Int): Pair<String, Int>? {
        var depth = 0
        var i = openIndex
        var closeParenIndex = -1
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        closeParenIndex = i
                        i++
                        break
                    }
                }
            }
            i++
        }
        if (closeParenIndex < 0) return null

        while (i < text.length && text[i].isWhitespace()) i++
        if (i < text.length && text[i] == '{') {
            var braceDepth = 0
            while (i < text.length) {
                when (text[i]) {
                    '{' -> braceDepth++
                    '}' -> {
                        braceDepth--
                        if (braceDepth == 0) {
                            return text.substring(openIndex, i + 1) to (i + 1)
                        }
                    }
                }
                i++
            }
            return null
        }
        return text.substring(openIndex, closeParenIndex + 1) to (closeParenIndex + 1)
    }
}
