package com.kaavalan.note.features.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for a real, adversarial-QA-found bug on the
 * final "Get started" onboarding page's "Add a few sample people
 * to play with" [androidx.compose.material3.Switch] row (see
 * [com.kaavalan.note.features.onboarding.OnboardingScreen]'s
 * `GetStartedPage`).
 *
 * **The finding.** The row wrapped the label `Text` and the
 * `Switch` in a plain `Row` with no `Modifier.clickable` /
 * `Modifier.toggleable` of its own -- only the small physical
 * `Switch(checked = loadSample, onCheckedChange = onSampleToggled)`
 * thumb was interactive. Tapping the label text -- the larger,
 * more natural target most users would aim for -- silently did
 * nothing: no state change, no visual feedback. A user who
 * believed they had opted into sample data by tapping the label
 * landed on a genuinely empty first-run Home instead. Settings'
 * `PrivacyRow` (in
 * [com.kaavalan.note.ui.settings.SettingsSheet]) already
 * establishes the "wrap the whole row so it is one tap target"
 * pattern for this app; the fix applies the same idea to a
 * checked toggle via `Modifier.toggleable(role = Role.Switch)`
 * on the `Row`, with the inner `Switch`'s own `onCheckedChange`
 * set to `null` so the Row stays the single source of truth for
 * both the tap handling and the TalkBack "switch" announcement.
 *
 * **Why a static source scan instead of a Compose click test.**
 * [com.kaavalan.note.features.capture.NoteBarTest] and
 * [com.kaavalan.note.ui.home.HomeScreenTest] document why:
 * Robolectric 4.13's launcher-activity resolution (PR #4736)
 * makes `createComposeRule()` /
 * `createAndroidComposeRule<ComponentActivity>()` fail in this
 * project's unit-test classpath regardless of manifest shape.
 * This test follows the same established pattern: assert the
 * *structure* that determines click routing (the `toggleable`
 * modifier lives on the outer `Row`, covering the label, rather
 * than on the inner `Switch` alone) rather than driving a real
 * click. A regression back to "only the Switch is interactive"
 * fails this test the same way the original bug should have been
 * caught before it shipped.
 */
class OnboardingSampleToggleTest {

    @Test
    fun `sample-data toggle Row is a single toggleable target covering label and Switch`() {
        val text = readOnboardingScreenSource()
        assertNotNull(
            "OnboardingScreen.kt must be readable off disk at $ONBOARDING_SCREEN_PATH",
            text,
        )
        val body = findComposableBodyRange(text!!, "private fun GetStartedPage(")
        assertNotNull(
            "could not locate the GetStartedPage composable body",
            body,
        )
        val getStartedSrc = text.substring(body!!.first, body.second)

        val toggleRow = findRowBodyRange(getStartedSrc, mustContain = "Switch(checked = loadSample")
        assertNotNull(
            "could not locate the sample-data toggle Row in GetStartedPage's body",
            toggleRow,
        )
        val rowSrc = getStartedSrc.substring(toggleRow!!.first, toggleRow.second)

        assertTrue(
            "the sample-data toggle Row must carry Modifier.toggleable(value = loadSample, " +
                "onValueChange = onSampleToggled, role = Role.Switch) directly, so the whole " +
                "label+Switch area is one tap target -- the exact fix for the finding where " +
                "tapping the label silently did nothing. Found:\n$rowSrc",
            REGEX_TOGGLEABLE_ON_ROW.containsMatchIn(rowSrc),
        )
        assertTrue(
            "the Row's toggleable must be wired to onSampleToggled -- otherwise tapping the " +
                "row would not actually flip the sample-data flag. Found:\n$rowSrc",
            rowSrc.contains(Regex("""onValueChange\s*=\s*onSampleToggled""")),
        )
        assertTrue(
            "the inner Switch must have onCheckedChange = null once the Row owns the " +
                "toggleable -- keeping a second live onCheckedChange = onSampleToggled wired " +
                "on the Switch re-creates a redundant nested-interactive-element node (the " +
                "Switch stays independently focusable inside the already-toggleable Row), " +
                "which is exactly the kind of accessibility regression the single-owner " +
                "pattern is meant to prevent. Found:\n$rowSrc",
            REGEX_SWITCH_ONCHECKEDCHANGE_NULL.containsMatchIn(rowSrc),
        )
    }

    private fun readOnboardingScreenSource(): String? {
        val candidates = listOf(
            File(ONBOARDING_SCREEN_PATH),
            File("app/$ONBOARDING_SCREEN_PATH"),
        )
        for (f in candidates) {
            if (f.exists()) return f.readText(Charsets.UTF_8)
        }
        return null
    }

    /**
     * Finds a top-level (or private top-level) function's body
     * range: `signature` through the matching close of its
     * trailing `{ ... }` body. Walks the parameter list's
     * *parens* to their balanced close first, then finds the body
     * `{` after that -- same approach as
     * [com.kaavalan.note.features.capture.NoteBarTest], needed
     * because `GetStartedPage`'s parameter list itself contains no
     * braces but sibling composables in this file do use default
     * lambda values elsewhere in the file.
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

    /**
     * Finds the full extent of a `Row(...) { ... }` call -- from
     * `Row(` through the matching close of its trailing content
     * lambda -- whose combined argument-list + body is the first
     * to reference [mustContain]. Same brace/paren-balanced
     * approach as
     * [com.kaavalan.note.features.capture.NoteBarTest]'s
     * `findColumnBodyRange`, adapted to `Row` so it can pick the
     * sample-data toggle row out from among the other `Row(...)`
     * blocks in the file.
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
            var braceDepth = 0
            var j = contentOpen
            var contentClose = -1
            while (j < text.length) {
                when (text[j]) {
                    '{' -> braceDepth += 1
                    '}' -> {
                        braceDepth -= 1
                        if (braceDepth == 0) {
                            contentClose = j + 1
                            break
                        }
                    }
                }
                j += 1
            }
            if (contentClose < 0) return null
            val fullRange = rowIdx to contentClose
            if (text.substring(rowIdx, contentClose).contains(mustContain)) {
                return fullRange
            }
            searchFrom = contentClose
        }
    }

    private companion object {
        const val ONBOARDING_SCREEN_PATH =
            "src/main/java/com/kaavalan/note/features/onboarding/OnboardingScreen.kt"

        val REGEX_TOGGLEABLE_ON_ROW = Regex(
            """\.toggleable\s*\(\s*value\s*=\s*loadSample""",
        )
        val REGEX_SWITCH_ONCHECKEDCHANGE_NULL = Regex(
            """Switch\s*\(\s*checked\s*=\s*loadSample\s*,\s*onCheckedChange\s*=\s*null""",
        )
    }
}
