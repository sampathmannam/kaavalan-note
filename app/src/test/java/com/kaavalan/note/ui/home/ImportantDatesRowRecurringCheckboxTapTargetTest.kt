package com.kaavalan.note.ui.home

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.0.x (BUG FIX regression guard, found via adversarial QA audit of
 * Person Detail > Important Dates > Add date dialog): pins the fix for
 * a real, reproducible dead-tap-target bug on the "Repeats every year"
 * checkbox row inside [AddImportantDateDialog].
 *
 * **The finding.** Person Detail -> tap "+" next to Important dates ->
 * in the Add date dialog, tap directly on the *words* "Repeats every
 * year" (not the small square checkbox to their left) -> Save. Confirmed
 * live via raw UI-dump attribute inspection: the `Checkbox` node itself
 * was `bounds=[184,1330][310,1456] clickable=true`, while the adjacent
 * `Text` node "Repeats every year" was
 * `bounds=[309,1366][610,1419] clickable=false` -- a completely
 * separate, non-overlapping region. Tapping the checkbox glyph directly
 * did toggle it (checked flips true); tapping the label text left it
 * unchecked, silently. This is the identical anti-pattern class as the
 * already-fixed onboarding "Add a few sample people" Switch-label bug
 * (see [com.kaavalan.note.features.onboarding.OnboardingSampleToggleTest]):
 * a plain `Row(Checkbox/Switch; Text)` with no `Modifier.toggleable`
 * wrapping the row, so only the small physical glyph responds.
 *
 * **The fix.** [AddImportantDateDialog]'s recurring-checkbox `Row` now
 * carries `Modifier.toggleable(value = recurring, onValueChange = { recurring
 * = it }, role = Role.Checkbox)` directly, making the whole row (label
 * included) a single tap target -- mirroring the exact fix already
 * applied to `OnboardingScreen.kt`'s `GetStartedPage` sample-toggle Row.
 * The inner `Checkbox`'s own `onCheckedChange` is set to `null` so the
 * Row's `toggleable` stays the single source of truth for both the tap
 * handling and the TalkBack "checkbox" role announcement -- keeping both
 * wired would double-toggle on a direct tap of the checkbox glyph.
 *
 * **Why a static source scan instead of a Compose click test.**
 * [HomeScreenTest] and [com.kaavalan.note.features.capture.NoteBarTest]
 * document why: Robolectric 4.13's launcher-activity resolution
 * (PR #4736) makes `createComposeRule()` /
 * `createAndroidComposeRule<ComponentActivity>()` fail in this project's
 * unit-test classpath regardless of manifest shape. This test follows
 * the same established pattern as [ImportantDatesRowDoubleTapGuardTest]
 * and [com.kaavalan.note.features.onboarding.OnboardingSampleToggleTest]:
 * assert the *structure* that determines click routing (the `toggleable`
 * modifier lives on the outer `Row`, covering the label, rather than on
 * the inner `Checkbox` alone) rather than driving a real click. A
 * regression back to "only the Checkbox is interactive" fails this test
 * the same way the original bug should have been caught before it
 * shipped.
 */
class ImportantDatesRowRecurringCheckboxTapTargetTest {

    @Test
    fun `recurring checkbox Row is a single toggleable target covering label and Checkbox`() {
        val text = readImportantDatesRowSource()
        assertNotNull("ImportantDatesRow.kt must be readable off disk at $FILE_PATH", text)

        val body = findComposableBodyRange(text!!, "private fun AddImportantDateDialog(")
        assertNotNull(
            "could not locate the AddImportantDateDialog composable body",
            body,
        )
        val dialogSrc = text.substring(body!!.first, body.second)

        val recurringRow = findRowBodyRange(dialogSrc, mustContain = "Checkbox(checked = recurring")
        assertNotNull(
            "could not locate the recurring-checkbox Row in AddImportantDateDialog's body",
            recurringRow,
        )
        val rowSrc = dialogSrc.substring(recurringRow!!.first, recurringRow.second)

        assertTrue(
            "the recurring-checkbox Row must carry Modifier.toggleable(value = recurring, " +
                "onValueChange = ..., role = Role.Checkbox) directly, so the whole " +
                "label+Checkbox area is one tap target -- the exact fix for the finding where " +
                "tapping the \"Repeats every year\" label silently did nothing. Found:\n$rowSrc",
            REGEX_TOGGLEABLE_ON_ROW.containsMatchIn(rowSrc),
        )
        assertTrue(
            "the Row's toggleable must actually flip `recurring` -- otherwise tapping the row " +
                "would not change the checkbox state. Found:\n$rowSrc",
            rowSrc.contains(Regex("""onValueChange\s*=\s*\{\s*recurring\s*=\s*it\s*}""")),
        )
        assertTrue(
            "the inner Checkbox must have onCheckedChange = null once the Row owns the " +
                "toggleable -- keeping a second live onCheckedChange = { recurring = it } wired " +
                "on the Checkbox re-creates a redundant nested-interactive-element node (the " +
                "Checkbox stays independently focusable inside the already-toggleable Row), " +
                "which is exactly the kind of accessibility regression the single-owner " +
                "pattern is meant to prevent. Found:\n$rowSrc",
            REGEX_CHECKBOX_ONCHECKEDCHANGE_NULL.containsMatchIn(rowSrc),
        )
    }

    private fun readImportantDatesRowSource(): String? {
        val candidates = listOf(File(FILE_PATH), File("app/$FILE_PATH"))
        for (f in candidates) {
            if (f.exists()) return f.readText(Charsets.UTF_8)
        }
        return null
    }

    /**
     * Finds a top-level (or private top-level) function's body range:
     * `signature` through the matching close of its trailing `{ ... }`
     * body. Same approach as [ImportantDatesRowDoubleTapGuardTest] /
     * [com.kaavalan.note.features.capture.NoteBarTest].
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
        return matchBrace(text, openBrace)
    }

    /**
     * Finds the full extent of a `Row(...) { ... }` call -- from `Row(`
     * through the matching close of its trailing content lambda --
     * whose combined argument-list + body is the first to reference
     * [mustContain]. Same brace/paren-balanced approach as
     * [com.kaavalan.note.features.onboarding.OnboardingSampleToggleTest]'s
     * `findRowBodyRange`, needed to pick the recurring-checkbox row out
     * from among the other `Row(...)` blocks in the dialog.
     */
    private fun findRowBodyRange(text: String, mustContain: String): Pair<Int, Int>? {
        var searchFrom = 0
        while (true) {
            val rowIdx = text.indexOf("Row(", searchFrom)
            if (rowIdx < 0) return null
            val argsOpen = rowIdx + "Row".length // index of the '('
            var depth = 0
            var i = argsOpen
            var argsClose = -1
            while (i < text.length) {
                when (text[i]) {
                    '(' -> depth += 1
                    ')' -> {
                        depth -= 1
                        if (depth == 0) {
                            argsClose = i
                            break
                        }
                    }
                }
                i += 1
            }
            if (argsClose < 0) return null
            val contentOpen = text.indexOf('{', argsClose)
            if (contentOpen < 0) return null
            val contentRange = matchBrace(text, contentOpen) ?: return null
            val fullRange = rowIdx to contentRange.second
            if (text.substring(rowIdx, contentRange.second).contains(mustContain)) {
                return fullRange
            }
            searchFrom = contentRange.second
        }
    }

    private fun matchBrace(text: String, openBrace: Int): Pair<Int, Int>? {
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

    private companion object {
        const val FILE_PATH = "src/main/java/com/kaavalan/note/ui/home/ImportantDatesRow.kt"

        // Matches the Row's `.toggleable(value = recurring, ...)` call, wherever the
        // Row's other params/modifier chain sit relative to it, so this doesn't
        // over-fit exact modifier ordering chosen by the fix.
        val REGEX_TOGGLEABLE_ON_ROW = Regex(
            """\.toggleable\s*\(\s*value\s*=\s*recurring""",
        )
        val REGEX_CHECKBOX_ONCHECKEDCHANGE_NULL = Regex(
            """Checkbox\s*\(\s*checked\s*=\s*recurring\s*,\s*onCheckedChange\s*=\s*null""",
        )
    }
}
