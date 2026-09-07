package com.kaavalan.note.ui.hierarchy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.1 (a11y regression, found via adversarial QA audit): pins the
 * fix for a real a11y gap in [AudiencePickerSheet]'s Person/
 * Designation/Station list rows.
 *
 * **The finding.** `DesignationList`, `StationList` and `PersonList`
 * each rendered their rows with a bare `Modifier.clickable { onPick(...) } `
 * -- no `onClickLabel`, no `contentDescription`. Every other clickable
 * list row in the app sets one: `InstructionRow` and `PersonRowSimple`
 * in `HomeHierarchySections.kt` both use
 * `.clickable(onClickLabel = "Open ...", onClick = onClick)`, and
 * `PersonRow` in `HomeScreen.kt` does the same via a `stringResource`.
 * Without it, TalkBack falls back to the generic "double tap to
 * activate" on every row in the audience picker -- the one sheet in
 * the app whose entire job is "pick who this goes to", read aloud
 * with no hint of what tapping a row does.
 *
 * **Why a static source scan instead of a Compose click test.**
 * [com.kaavalan.note.ui.home.HomeScreenTest] and
 * [com.kaavalan.note.features.capture.NoteBarTest] document why:
 * Robolectric 4.13's launcher-activity resolution (PR #4736) makes
 * `createComposeRule()` / `createAndroidComposeRule<ComponentActivity>()`
 * fail in this project's unit-test classpath regardless of manifest
 * shape. This test follows the same established pattern: assert the
 * *structure* that determines what TalkBack announces (every
 * `.clickable(...)` on a picker row carries `onClickLabel`) rather
 * than driving a real click. A regression back to a bare
 * `Modifier.clickable { ... }` on any of the three list rows fails
 * this test the same way the original bug should have been caught
 * before it shipped.
 */
class AudiencePickerSheetTest {

    @Test
    fun `DesignationList row clickable carries onClickLabel`() {
        assertRowHasOnClickLabel("DesignationList")
    }

    @Test
    fun `StationList row clickable carries onClickLabel`() {
        assertRowHasOnClickLabel("StationList")
    }

    @Test
    fun `PersonList row clickable carries onClickLabel`() {
        assertRowHasOnClickLabel("PersonList")
    }

    @Test
    fun `no bare Modifier clickable without onClickLabel remains in the file`() {
        val text = readSource()
        assertNotNull("AudiencePickerSheet.kt must be readable off disk at $SHEET_PATH", text)

        // A "bare" clickable is `.clickable {` (trailing-lambda form,
        // no arguments) or `.clickable(onClick = ...)` with no
        // `onClickLabel` alongside it. The fixed rows all use
        // `.clickable(onClickLabel = ..., trailing-lambda)`, which
        // this regex does not match.
        val bareClickable = Regex("""\.clickable\s*\{""")
        assertFalse(
            "found a bare `.clickable { ... }` (no onClickLabel) in AudiencePickerSheet.kt -- " +
                "every list row in this sheet must set onClickLabel so TalkBack announces the " +
                "action, matching InstructionRow / PersonRowSimple's convention. Offending file:\n" +
                text,
            bareClickable.containsMatchIn(text!!),
        )
    }

    // -----------------------------------------------------------------
    // Back-navigation regression (adversarial QA audit, Dispatch >
    // AudiencePickerSheet navigation).
    //
    // **The finding.** Once drilled into Person/Designation/Station,
    // [AudiencePickerSheet] had no back control -- the only way out
    // was dismissing the whole `ModalBottomSheet` (scrim tap or the
    // back gesture). Dismissal tears the composable down, so the
    // `remember { mutableStateOf<Mode>(Mode.Root) }` in
    // `AudiencePickerSheet` is lost with it: switching from e.g.
    // Designation to Station required fully closing and reopening the
    // picker.
    //
    // **The fix.** Mirrors AddPersonSheet's state-conditional
    // `BackHandler` convention (there: `imeVisible` decides whether
    // back hides the keyboard or dismisses the sheet; here: `mode`
    // decides whether back returns to Root or falls through to
    // `onDismiss`), plus a visible back `IconButton` in the header --
    // the same in-sheet-affordance pattern SettingsSheet uses for its
    // close button, because a scrim-tap/gesture-only dismissal isn't
    // discoverable. Both paths reset `mode` to `Mode.Root` rather than
    // calling `onDismiss`, so the sheet stays open and the user can
    // pick a different branch immediately.
    //
    // **Why a static source scan.** Same Robolectric-classpath
    // constraint as the tests above -- this asserts the structural
    // invariant (a state-conditional `BackHandler` exists, and the
    // header's back control mutates `mode` rather than calling
    // `onDismiss`) rather than driving a real Compose back-press.
    // -----------------------------------------------------------------

    @Test
    fun `BackHandler is enabled only while drilled past Root and returns to Root, not onDismiss`() {
        val text = readSource()
        assertNotNull("AudiencePickerSheet.kt must be readable off disk at $SHEET_PATH", text)

        assertTrue(
            "AudiencePickerSheet must install BackHandler(enabled = mode != Mode.Root) { " +
                "mode = Mode.Root }, so hardware/gesture back returns to the root chips " +
                "instead of dismissing the whole sheet (mirrors AddPersonSheet's " +
                "imeVisible-conditional BackHandler pair). Found:\n$text",
            REGEX_BACKHANDLER_TO_ROOT.containsMatchIn(text!!),
        )
        assertFalse(
            "a BackHandler(enabled = mode != Mode.Root) { onDismiss() } would reproduce the " +
                "exact bug this test guards -- back must step up to Root, not tear down the " +
                "whole sheet. Found:\n$text",
            REGEX_BACKHANDLER_TO_DISMISS.containsMatchIn(text),
        )
    }

    @Test
    fun `header back IconButton is guarded to non-Root and returns to Root, not onDismiss`() {
        val text = readSource()
        assertNotNull("AudiencePickerSheet.kt must be readable off disk at $SHEET_PATH", text)
        val body = findComposableBodyRange(text!!, "fun AudiencePickerSheet(")
        assertNotNull("could not locate the AudiencePickerSheet composable body", body)
        val src = text.substring(body!!.first, body.second)

        assertTrue(
            "AudiencePickerSheet's header must guard its back IconButton with " +
                "`if (mode != Mode.Root)` so the affordance only appears once drilled in " +
                "(Root already has its own chips to navigate with). Found:\n$src",
            REGEX_BACK_BUTTON_GUARD.containsMatchIn(src),
        )
        assertTrue(
            "the header back IconButton's onClick must set `mode = Mode.Root` -- that is " +
                "the in-sheet affordance this bug was missing (mirrors SettingsSheet's " +
                "visible close IconButton next to its title). Found:\n$src",
            REGEX_BACK_ICONBUTTON_TO_ROOT.containsMatchIn(src),
        )
        assertFalse(
            "the header back IconButton must NOT call onDismiss() on click -- that would " +
                "reproduce the bug (closing the whole sheet instead of stepping back one " +
                "level). Found:\n$src",
            REGEX_BACK_ICONBUTTON_TO_DISMISS.containsMatchIn(src),
        )
    }

    @Test
    fun `hierarchy_audience_picker_back string resource is present and non-blank`() {
        val stringsFile = resolveExisting(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
        )
        assertNotNull("strings.xml must exist", stringsFile)
        val xml = stringsFile!!.readText(Charsets.UTF_8)
        val pattern = Regex("""<string\s+name\s*=\s*"hierarchy_audience_picker_back"\s*>([^<]*)</string>""")
        val match = pattern.find(xml)
        assertNotNull("strings.xml must declare <string name=\"hierarchy_audience_picker_back\">", match)
        assertTrue(
            "strings.xml entry 'hierarchy_audience_picker_back' must be non-blank",
            match!!.groupValues[1].isNotBlank(),
        )
    }

    @Test
    fun `a11y_audience select string resources are present and non-blank`() {
        val stringsFile = resolveExisting(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
        )
        assertNotNull("strings.xml must exist", stringsFile)
        val xml = stringsFile!!.readText(Charsets.UTF_8)
        listOf(
            "a11y_audience_person_select",
            "a11y_audience_designation_select",
            "a11y_audience_station_select",
        ).forEach { name ->
            val pattern = Regex("""<string\s+name\s*=\s*"$name"\s*>([^<]*)</string>""")
            val match = pattern.find(xml)
            assertNotNull("strings.xml must declare <string name=\"$name\">", match)
            assertTrue(
                "strings.xml entry '$name' must be non-blank",
                match!!.groupValues[1].isNotBlank(),
            )
        }
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private fun assertRowHasOnClickLabel(functionName: String) {
        val text = readSource()
        assertNotNull("AudiencePickerSheet.kt must be readable off disk at $SHEET_PATH", text)
        val body = findComposableBodyRange(text!!, "fun $functionName(")
        assertNotNull("could not locate the $functionName composable body", body)
        val src = text.substring(body!!.first, body.second)

        assertTrue(
            "$functionName's row must carry Modifier.clickable(onClickLabel = ..., ...) so " +
                "TalkBack announces the action instead of the generic \"double tap to " +
                "activate\". Found:\n$src",
            REGEX_CLICKABLE_WITH_LABEL.containsMatchIn(src),
        )
        assertFalse(
            "$functionName must NOT have a bare Modifier.clickable { ... } with no " +
                "onClickLabel -- that is the exact regression this test guards. Found:\n$src",
            REGEX_BARE_CLICKABLE.containsMatchIn(src),
        )
    }

    private fun readSource(): String? {
        return resolveExisting(SHEET_PATH, "app/$SHEET_PATH")?.readText(Charsets.UTF_8)
    }

    private fun resolveExisting(vararg paths: String): File? =
        paths.map { File(it) }.firstOrNull { it.exists() }

    /**
     * Finds a top-level private function's body range: `signature`
     * through the matching close of its trailing `{ ... }` body.
     * Walks the parameter list's *parens* to their balanced close
     * first, then finds the body `{` after that -- the same approach
     * [com.kaavalan.note.features.capture.NoteBarTest] uses, needed
     * because these signatures have lambda-typed parameters
     * (`onPick: (String) -> Unit`) whose own parens would otherwise
     * confuse a naive "first `{` after signature" scan.
     */
    private fun findComposableBodyRange(text: String, signature: String): Pair<Int, Int>? {
        val sigIdx = text.indexOf(signature)
        if (sigIdx < 0) return null
        val paramsOpen = text.indexOf('(', sigIdx)
        if (paramsOpen < 0) return null
        var parenDepth = 0
        var i = paramsOpen
        var paramsClose = -1
        while (i < text.length) {
            when (text[i]) {
                '(' -> parenDepth += 1
                ')' -> {
                    parenDepth -= 1
                    if (parenDepth == 0) {
                        paramsClose = i
                        break
                    }
                }
            }
            i += 1
        }
        if (paramsClose < 0) return null
        val openBrace = text.indexOf('{', paramsClose)
        if (openBrace < 0) return null
        var depth = 0
        var j = openBrace
        while (j < text.length) {
            when (text[j]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return openBrace to (j + 1)
                }
            }
            j += 1
        }
        return null
    }

    companion object {
        private const val SHEET_PATH = "src/main/java/com/kaavalan/note/ui/hierarchy/AudiencePickerSheet.kt"
        private val REGEX_CLICKABLE_WITH_LABEL = Regex("""\.clickable\s*\(\s*onClickLabel\s*=""")
        private val REGEX_BARE_CLICKABLE = Regex("""\.clickable\s*\{""")

        // Back-navigation regression (see the block comment above the tests below).
        private val REGEX_BACKHANDLER_TO_ROOT = Regex(
            """BackHandler\s*\(\s*enabled\s*=\s*mode\s*!=\s*Mode\.Root\s*\)\s*\{\s*mode\s*=\s*Mode\.Root\s*\}""",
        )
        private val REGEX_BACKHANDLER_TO_DISMISS = Regex(
            """BackHandler\s*\(\s*enabled\s*=\s*mode\s*!=\s*Mode\.Root\s*\)\s*\{\s*onDismiss\s*\(\s*\)\s*\}""",
        )
        private val REGEX_BACK_BUTTON_GUARD = Regex("""if\s*\(\s*mode\s*!=\s*Mode\.Root\s*\)\s*\{""")
        private val REGEX_BACK_ICONBUTTON_TO_ROOT = Regex(
            """IconButton\s*\(\s*onClick\s*=\s*\{\s*mode\s*=\s*Mode\.Root\s*\}\s*\)""",
        )
        private val REGEX_BACK_ICONBUTTON_TO_DISMISS = Regex(
            """IconButton\s*\(\s*onClick\s*=\s*(onDismiss\s*,|onDismiss\s*\)|\{\s*onDismiss\s*\(\s*\)\s*\})""",
        )
    }
}
