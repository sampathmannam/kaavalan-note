package com.kaavalan.note.ui.hierarchy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.1 (UI-bug regression, found via adversarial QA audit of
 * Dispatch > [InboundDraftSheet]): pins the fix for a real,
 * reproducible bug in the "Save as outgoing" button.
 *
 * **The finding.** Unlike [DispatchComposerSheet]'s "Next" button
 * (`enabled = state.audience != null && rawText.isNotBlank() && state.rosterReady`)
 * or [com.kaavalan.note.ui.home.PersonLinksRow]'s confirmButton
 * (`enabled = resolvedRelation.isNotBlank() && targetId.isNotBlank()`),
 * `InboundDraftSheet`'s "Save as outgoing" Button carried no
 * `enabled` parameter at all:
 * `Button(onClick = { onSaveAsOutgoing(title, rawText) }, modifier = Modifier.fillMaxWidth())`
 * fired unconditionally -- including with both the Title and Body
 * fields left completely blank.
 *
 * **The fix.** Mirrors the established codebase pattern for a save
 * button backed by more than one free-text field that feeds
 * directly into the saved record (see `PersonLinksRow` above): gate
 * on both fields being non-blank,
 * `enabled = title.isNotBlank() && rawText.isNotBlank()`.
 *
 * **Why a static source scan instead of a Compose click test.**
 * [com.kaavalan.note.ui.home.HomeScreenTest],
 * [com.kaavalan.note.features.capture.NoteBarTest] and
 * [AudiencePickerSheetTest] document why: Robolectric 4.13's
 * launcher-activity resolution (PR #4736) makes `createComposeRule()`
 * / `createAndroidComposeRule<ComponentActivity>()` fail in this
 * project's unit-test classpath regardless of manifest shape. This
 * test follows the same established pattern: assert the *structure*
 * that determines whether the button can fire (an `enabled` param
 * tied to both fields' blankness) rather than driving a real
 * Compose click. A regression back to an ungated Button fails this
 * test the same way the original bug should have been caught before
 * it shipped.
 */
class InboundDraftSheetTest {

    @Test
    fun `Save as outgoing Button is gated on Title and Body both non-blank`() {
        val text = readSource()
        assertNotNull("InboundDraftSheet.kt must be readable off disk at $SHEET_PATH", text)
        val body = findComposableBodyRange(text!!, "fun InboundDraftSheet(")
        assertNotNull("could not locate the InboundDraftSheet composable body", body)
        val src = text.substring(body!!.first, body.second)

        val saveButton = findCallBodyRange(src, callName = "Button", afterMarker = "onSaveAsOutgoing")
        assertNotNull(
            "could not locate the \"Save as outgoing\" Button call in InboundDraftSheet's body",
            saveButton,
        )
        val saveButtonSrc = src.substring(saveButton!!.first, saveButton.second)

        assertTrue(
            "the \"Save as outgoing\" Button must carry " +
                "enabled = title.isNotBlank() && rawText.isNotBlank(), mirroring " +
                "DispatchComposerSheet's \"Next\" button and PersonLinksRow's confirmButton " +
                "-- both Title and Body feed directly into the saved outgoing note, so both " +
                "must be filled in before the button can fire. Found:\n$saveButtonSrc",
            REGEX_ENABLED_GATE.containsMatchIn(saveButtonSrc),
        )
    }

    @Test
    fun `no unconditional Save as outgoing Button remains`() {
        val text = readSource()
        assertNotNull("InboundDraftSheet.kt must be readable off disk at $SHEET_PATH", text)

        // The exact shape of the original bug: onClick immediately
        // followed by modifier, with nothing gating it in between.
        // If this ever matches again, the enabled-gate regressed even
        // if some other `enabled = ...` happens to appear elsewhere
        // in the file.
        assertFalse(
            "found the exact unconditional Button call this test guards against -- a Button " +
                "whose only parameters are onClick and modifier (no enabled gate) reproduces " +
                "the original bug: it fires onSaveAsOutgoing(title, rawText) even with both " +
                "Title and Body blank. Found:\n$text",
            REGEX_UNGATED_BUTTON.containsMatchIn(text!!),
        )
    }

    private fun readSource(): String? {
        return resolveExisting(SHEET_PATH, "app/$SHEET_PATH")?.readText(Charsets.UTF_8)
    }

    private fun resolveExisting(vararg paths: String): File? =
        paths.map { File(it) }.firstOrNull { it.exists() }

    /**
     * Finds a top-level function's body range: `signature` through
     * the matching close of its trailing `{ ... }` body. Walks the
     * parameter list's *parens* to their balanced close first, then
     * finds the body `{` after that -- the same approach
     * [com.kaavalan.note.features.capture.NoteBarTest] and
     * [AudiencePickerSheetTest] use, needed because InboundDraftSheet's
     * signature has lambda-typed parameters
     * (`onSaveAsOutgoing: (title: String, rawText: String) -> Unit`)
     * whose own parens would otherwise confuse a naive "first `{`
     * after signature" scan.
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
     * Finds the full extent of a `<callName>(...) { ... }` call --
     * from `<callName>(` through the matching close of its *trailing
     * content lambda* (if any) -- whose combined argument list +
     * body is the first to reference [afterMarker]. Same approach as
     * [com.kaavalan.note.features.capture.NoteBarTest]'s
     * `findColumnBodyRange`, generalised to an optional trailing
     * lambda so it also matches calls like `Button(...)` whose
     * content lambda comes after a fully-parenthesised argument list.
     */
    private fun findCallBodyRange(text: String, callName: String, afterMarker: String): Pair<Int, Int>? {
        var searchFrom = 0
        while (true) {
            val callIdx = text.indexOf("$callName(", searchFrom)
            if (callIdx < 0) return null
            val argsOpen = callIdx + callName.length // index of the '('
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

            var contentClose = argsClose + 1
            var k = argsClose + 1
            while (k < text.length && text[k].isWhitespace()) k += 1
            if (k < text.length && text[k] == '{') {
                var braceDepth = 0
                var j = k
                var found = -1
                while (j < text.length) {
                    when (text[j]) {
                        '{' -> braceDepth += 1
                        '}' -> {
                            braceDepth -= 1
                            if (braceDepth == 0) {
                                found = j + 1
                                break
                            }
                        }
                    }
                    j += 1
                }
                if (found < 0) return null
                contentClose = found
            }

            val fullRange = callIdx to contentClose
            if (text.substring(callIdx, contentClose).contains(afterMarker)) {
                return fullRange
            }
            searchFrom = contentClose
        }
    }

    private companion object {
        const val SHEET_PATH = "src/main/java/com/kaavalan/note/ui/hierarchy/InboundDraftSheet.kt"

        val REGEX_ENABLED_GATE = Regex(
            """enabled\s*=\s*title\.isNotBlank\(\)\s*&&\s*rawText\.isNotBlank\(\)""",
        )
        val REGEX_UNGATED_BUTTON = Regex(
            """Button\s*\(\s*onClick\s*=\s*\{\s*onSaveAsOutgoing\s*\(\s*title\s*,\s*rawText\s*\)\s*}\s*,\s*modifier\s*=\s*Modifier\.fillMaxWidth\(\)\s*\)""",
        )
    }
}
