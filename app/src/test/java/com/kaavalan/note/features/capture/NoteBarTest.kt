package com.kaavalan.note.features.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.1.2 (BUG FIX regression guard, found via adversarial on-device
 * audit): pins the fix for a real, reproducible bug in [NoteBar]'s
 * Photo/Voice buttons.
 *
 * **The finding.** v1.7.1 added a visible caption ("Photo" / "Voice")
 * below each icon specifically so users could see and tap a labelled
 * button instead of an unlabelled icon — the commit comment said "the
 * IconButton wraps the whole Column so the clickable target is the
 * full icon+label pair, not just the icon." It did not: the
 * `IconButton` wrapped only the 36dp `Icon`; the caption `Text` below
 * it was a plain sibling in the `Column`, outside any clickable
 * modifier of its own.
 *
 * Verified on-device with `uiautomator`: the icon's bounds
 * (y=1951-2014) and the label's bounds (y=2034-2068) did not overlap
 * at all. Tapping the icon launched the system camera correctly;
 * tapping the label — the exact target a user reading "Photo" would
 * aim for — fell through to the parent `Surface`'s
 * `onClick = onTextClick` and silently opened the plain text capture
 * sheet instead. Same structure, same bug, for the Voice/mic button.
 *
 * **Why a static source scan instead of a Compose click test.**
 * [com.kaavalan.note.ui.home.HomeScreenTest] documents why:
 * Robolectric 4.13's launcher-activity resolution (PR #4736) makes
 * `createComposeRule()` / `createAndroidComposeRule<ComponentActivity>()`
 * fail in this project's unit-test classpath regardless of manifest
 * shape. This test follows the same established pattern: assert the
 * *structure* that determines click routing (the `clickable` modifier
 * lives on the outer `Column`, not on an inner `IconButton` that
 * excludes the label) rather than driving a real click. A regression
 * back to "IconButton wraps only the Icon, label is a bare sibling"
 * fails this test the same way the original bug should have been
 * caught before it shipped.
 */
class NoteBarTest {

    @Test
    fun `Photo column is a single clickable target covering icon and label`() {
        val text = readNoteBarSource()
        assertNotNull("NoteBar.kt must be readable off disk at $NOTE_BAR_PATH", text)
        val body = findComposableBodyRange(text!!, "fun NoteBar(")
        assertNotNull("could not locate the NoteBar composable body", body)
        val src = text.substring(body!!.first, body.second)

        val photoColumn = findColumnBodyRange(src, afterMarker = "photoLabel")
        assertNotNull(
            "could not locate the Photo Column in NoteBar's body",
            photoColumn,
        )
        val photoColumnSrc = src.substring(photoColumn!!.first, photoColumn.second)

        assertTrue(
            "the Photo Column must carry Modifier.clickable(onClick = onCameraClick, ...) " +
                "directly, so the whole icon+label area is one tap target. Found:\n$photoColumnSrc",
            REGEX_CLICKABLE_ON_CAMERA.containsMatchIn(photoColumnSrc),
        )
        assertFalse(
            "the Photo Icon must NOT be wrapped in its own IconButton -- that was the bug: " +
                "an IconButton around only the Icon (not the label) is a smaller tap target " +
                "than what the visible caption implies. Found:\n$photoColumnSrc",
            REGEX_ICONBUTTON_ON_CAMERA.containsMatchIn(photoColumnSrc),
        )
    }

    @Test
    fun `Voice column is a single clickable target covering icon and label`() {
        val text = readNoteBarSource()
        assertNotNull("NoteBar.kt must be readable off disk at $NOTE_BAR_PATH", text)
        val body = findComposableBodyRange(text!!, "fun NoteBar(")
        assertNotNull("could not locate the NoteBar composable body", body)
        val src = text.substring(body!!.first, body.second)

        val voiceColumn = findColumnBodyRange(src, afterMarker = "voiceLabel")
        assertNotNull(
            "could not locate the Voice Column in NoteBar's body",
            voiceColumn,
        )
        val voiceColumnSrc = src.substring(voiceColumn!!.first, voiceColumn.second)

        assertTrue(
            "the Voice Column must carry Modifier.clickable(onClick = onMicClick, ...) " +
                "directly, so the whole icon+label area is one tap target. Found:\n$voiceColumnSrc",
            REGEX_CLICKABLE_ON_MIC.containsMatchIn(voiceColumnSrc),
        )
        assertFalse(
            "the Voice Icon must NOT be wrapped in its own IconButton -- same bug class as " +
                "the Photo button. Found:\n$voiceColumnSrc",
            REGEX_ICONBUTTON_ON_MIC.containsMatchIn(voiceColumnSrc),
        )
    }

    private fun readNoteBarSource(): String? {
        val candidates = listOf(
            File(NOTE_BAR_PATH),
            File("app/$NOTE_BAR_PATH"),
        )
        for (f in candidates) {
            if (f.exists()) return f.readText(Charsets.UTF_8)
        }
        return null
    }

    /**
     * Finds a top-level function's body range: `signature` through the
     * matching close of its trailing `{ ... }` body.
     *
     * Unlike [com.kaavalan.note.ui.home.HomeScreenTest]'s version of
     * this helper (safe for signatures with no braces in the
     * parameter list), [NoteBar]'s signature has
     * `onCameraClick: () -> Unit = {}` / `onMicClick: () -> Unit = {}`
     * default values -- a bare `text.indexOf('{', sigIdx)` grabs that
     * empty-lambda default instead of the function body, and the
     * depth counter immediately returns to 0 on its very next
     * character. This version walks the parameter list's *parens* to
     * their balanced close first, then finds the body `{` after that.
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
     * Finds the full extent of a `Column(...) { ... }` call -- from
     * `Column(` through the matching close of its *trailing content
     * lambda* -- whose combined argument-list + body is the first to
     * reference [afterMarker] (e.g. "photoLabel" or "voiceLabel").
     * Distinguishes the Photo Column from the Voice Column, which are
     * structurally identical siblings that differ only in which
     * label/callback they close over.
     *
     * Kotlin's trailing-lambda syntax means `Column(` can be followed
     * by argument-list braces first (e.g. `.semantics { ... }` inside
     * a modifier chain) before the Column's own content lambda `{`
     * opens. A naive "first `{` after `Column(`" match grabs the
     * wrong one. This walks the argument-list *parens* to their
     * matching close first, then finds the content lambda after that.
     */
    private fun findColumnBodyRange(text: String, afterMarker: String): Pair<Int, Int>? {
        var searchFrom = 0
        while (true) {
            val colIdx = text.indexOf("Column(", searchFrom)
            if (colIdx < 0) return null
            val argsOpen = colIdx + "Column".length // index of the '('
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
            val fullRange = colIdx to contentClose
            if (text.substring(colIdx, contentClose).contains(afterMarker)) {
                return fullRange
            }
            searchFrom = contentClose
        }
    }

    private companion object {
        const val NOTE_BAR_PATH =
            "src/main/java/com/kaavalan/note/features/capture/NoteBar.kt"

        val REGEX_CLICKABLE_ON_CAMERA = Regex(
            """\.clickable\s*\(\s*onClick\s*=\s*onCameraClick""",
        )
        val REGEX_CLICKABLE_ON_MIC = Regex(
            """\.clickable\s*\(\s*onClick\s*=\s*onMicClick""",
        )
        val REGEX_ICONBUTTON_ON_CAMERA = Regex(
            """IconButton\s*\(\s*onClick\s*=\s*onCameraClick""",
        )
        val REGEX_ICONBUTTON_ON_MIC = Regex(
            """IconButton\s*\(\s*onClick\s*=\s*onMicClick""",
        )
    }
}
