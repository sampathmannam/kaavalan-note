package com.kaavalan.note.ui.hierarchy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.1 (BUG FIX regression guard, found via adversarial QA audit):
 * pins the fix for a real copy/paste bug in [DispatchComposerSheet]'s
 * title bar.
 *
 * **The finding.** [DispatchComposerSheet] -- the sheet where the
 * user writes the instruction text and picks an audience before
 * moving on to [DispatchSheet] -- rendered its own title `Text()`
 * with `stringResource(R.string.hierarchy_audience_picker_title)`,
 * i.e. [AudiencePickerSheet]'s own title string ("Send to…"), instead
 * of a title string of its own. [AudiencePickerSheet] legitimately
 * uses `hierarchy_audience_picker_title` for its title (see
 * `AudiencePickerSheet.kt`); `DispatchComposerSheet` is a different
 * screen entirely and must not reuse that key.
 *
 * **The fix.** Added a dedicated `hierarchy_composer_title` string
 * resource ("New instruction") and pointed
 * `DispatchComposerSheet`'s title `Text()` at it instead.
 *
 * **Why a static source scan instead of a Compose UI test.**
 * [com.kaavalan.note.ui.home.HomeScreenTest] and
 * [com.kaavalan.note.features.capture.NoteBarTest] document why:
 * Robolectric 4.13's launcher-activity resolution (PR #4736) makes
 * `createComposeRule()` / `createAndroidComposeRule<ComponentActivity>()`
 * fail in this project's unit-test classpath regardless of manifest
 * shape. This test follows the same established pattern: assert the
 * *structure* that determines what the user reads as the screen title
 * (which string-resource key the title `Text()` binds to) rather than
 * driving a real Compose render. A regression back to
 * `hierarchy_audience_picker_title` in `DispatchComposerSheet`'s title
 * fails this test the same way the original bug should have been
 * caught before it shipped.
 */
class DispatchComposerSheetTest {

    @Test
    fun `title Text binds to the composer's own string resource, not the audience picker's`() {
        val text = readSource()
        assertNotNull("DispatchComposerSheet.kt must be readable off disk at $SHEET_PATH", text)
        val body = findComposableBodyRange(text!!, "fun DispatchComposerSheet(")
        assertNotNull("could not locate the DispatchComposerSheet composable body", body)
        val src = text.substring(body!!.first, body.second)

        assertTrue(
            "DispatchComposerSheet's title Text() must bind to " +
                "R.string.hierarchy_composer_title -- its own title string. Found:\n$src",
            REGEX_TITLE_USES_COMPOSER_STRING.containsMatchIn(src),
        )
        assertFalse(
            "DispatchComposerSheet's title Text() must NOT bind to " +
                "R.string.hierarchy_audience_picker_title -- that is AudiencePickerSheet's own " +
                "title string ('Send to…'); reusing it here is the exact copy/paste bug this " +
                "test guards against. Found:\n$src",
            REGEX_TITLE_USES_AUDIENCE_PICKER_STRING.containsMatchIn(src),
        )
    }

    @Test
    fun `hierarchy_composer_title string resource is present, non-blank, and distinct from hierarchy_audience_picker_title`() {
        val stringsFile = resolveExisting(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
        )
        assertNotNull("strings.xml must exist", stringsFile)
        val xml = stringsFile!!.readText(Charsets.UTF_8)

        val composerMatch = Regex("""<string\s+name\s*=\s*"hierarchy_composer_title"\s*>([^<]*)</string>""").find(xml)
        assertNotNull("strings.xml must declare <string name=\"hierarchy_composer_title\">", composerMatch)
        val composerValue = composerMatch!!.groupValues[1]
        assertTrue("strings.xml entry 'hierarchy_composer_title' must be non-blank", composerValue.isNotBlank())

        val audienceMatch = Regex("""<string\s+name\s*=\s*"hierarchy_audience_picker_title"\s*>([^<]*)</string>""").find(xml)
        assertNotNull("strings.xml must declare <string name=\"hierarchy_audience_picker_title\">", audienceMatch)

        assertTrue(
            "hierarchy_composer_title ('$composerValue') must read differently from " +
                "hierarchy_audience_picker_title ('${audienceMatch!!.groupValues[1]}') -- the whole " +
                "point of the fix is that DispatchComposerSheet no longer shows the audience " +
                "picker's own title.",
            composerValue != audienceMatch.groupValues[1],
        )
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private fun readSource(): String? {
        return resolveExisting(SHEET_PATH, "app/$SHEET_PATH")?.readText(Charsets.UTF_8)
    }

    private fun resolveExisting(vararg paths: String): File? =
        paths.map { File(it) }.firstOrNull { it.exists() }

    /**
     * Finds a top-level function's body range: `signature` through the
     * matching close of its trailing `{ ... }` body. Walks the
     * parameter list's *parens* to their balanced close first, then
     * finds the body `{` after that -- the same approach
     * [com.kaavalan.note.features.capture.NoteBarTest] and
     * [AudiencePickerSheetTest] use, needed because
     * `DispatchComposerSheet`'s signature has lambda-typed parameters
     * (`onDismiss: () -> Unit`, `onSaved: (String) -> Unit`) whose own
     * parens would otherwise confuse a naive "first `{` after
     * signature" scan.
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
        private const val SHEET_PATH = "src/main/java/com/kaavalan/note/ui/hierarchy/DispatchComposerSheet.kt"
        private val REGEX_TITLE_USES_COMPOSER_STRING = Regex(
            """Text\s*\(\s*stringResource\s*\(\s*R\.string\.hierarchy_composer_title\s*\)""",
        )
        private val REGEX_TITLE_USES_AUDIENCE_PICKER_STRING = Regex(
            """Text\s*\(\s*stringResource\s*\(\s*R\.string\.hierarchy_audience_picker_title\s*\)""",
        )
    }
}
