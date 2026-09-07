package com.kaavalan.note.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.0.1 (BUG FIX regression guard, found via adversarial QA audit):
 * pins the fix for a real, reproducible bug in [PersonLinksRow]'s
 * "Add link" flow.
 *
 * **The finding.** The custom-relation `OutlinedTextField` in
 * `AddLinkDialog` had no length limit, so typing (or pasting) a
 * long value -- e.g. a sentence repeated 8x, 600+ chars -- saved
 * fine (confirmed byte-for-byte in the DB), but rendered back as
 * the link's `AssistChip` label with no width bound, no
 * `maxLines`, and no `TextOverflow`. A `LazyRow` item is measured
 * with unbounded main-axis width, so the label never wrapped and
 * never overflowed anything -- it just rendered as one very wide
 * single line that got silently clipped at the screen edge, with
 * no "..." affordance and no way to reveal the rest (the LazyRow
 * only scrolls between *different* chips). Nearly all of the saved
 * text became invisible with no indication more existed.
 *
 * **The fix** (mirrors two patterns already established elsewhere
 * in this codebase for this exact class of problem):
 *  1. Cap the custom-relation field's length via `it.take(N)` in
 *     `onValueChange`, the same idiom used for [DropDialog]'s
 *     `reason` field in PersonDetailScreen.kt
 *     (`reason = it.take(200)`) and for `QuickNoteActivity`'s note
 *     body (`text = it.take(MAX_NOTE_LENGTH)`).
 *  2. Bound the link chip's width (`Modifier.widthIn(max = ...)`)
 *     and give its label `maxLines = 1` +
 *     `overflow = TextOverflow.Ellipsis`, the same
 *     bounded-width-plus-ellipsis idiom already used for overflowing
 *     text elsewhere (e.g. `HomeHierarchySections.kt`,
 *     `HomeScreen.kt`, `DecaySection.kt`).
 *
 * **Why a static source scan instead of a Compose click/render
 * test.** [HomeScreenTest] and
 * [com.kaavalan.note.features.capture.NoteBarTest] document why:
 * Robolectric 4.13's launcher-activity resolution (PR #4736) makes
 * `createComposeRule()` / `createAndroidComposeRule<ComponentActivity>()`
 * fail in this project's unit-test classpath regardless of manifest
 * shape, and this bug is about text *layout* overflow behavior,
 * which needs a real layout pass to observe pixel-for-pixel anyway.
 * This test instead pins the *structural* invariants that
 * determine whether the label can ever show truncation: the
 * `onValueChange` lambda must bound the input's length, and the
 * chip that renders it must carry both a width bound and
 * `maxLines` + `TextOverflow.Ellipsis` on its label. A regression
 * back to either "unbounded custom relation input" or "unbounded
 * chip width with no ellipsis" fails this test the same way the
 * original bug should have been caught before it shipped.
 */
class PersonLinksRowTest {

    @Test
    fun `custom relation field bounds its length on every keystroke`() {
        val src = readPersonLinksRowSource()
        assertNotNull("PersonLinksRow.kt must be readable off disk at $PERSON_LINKS_ROW_PATH", src)
        val text = src!!

        val dialogBody = findComposableBodyRange(text, "private fun AddLinkDialog(")
        assertNotNull("could not locate the AddLinkDialog composable body", dialogBody)
        val dialogSrc = text.substring(dialogBody!!.first, dialogBody.second)

        assertTrue(
            "the custom-relation OutlinedTextField's onValueChange must clamp the incoming " +
                "value with `.take(N)` -- an unbounded onValueChange = { customRelation = it } " +
                "is exactly the bug: it lets an arbitrarily long string (e.g. 600+ chars pasted " +
                "in) reach the saved link's relation and, from there, the chip label. Found:\n$dialogSrc",
            REGEX_CUSTOM_RELATION_TAKE.containsMatchIn(dialogSrc),
        )
        assertFalse(
            "the custom-relation OutlinedTextField must not assign the raw, unclamped `it` " +
                "straight to customRelation -- that regresses to the unbounded-input bug even " +
                "if a chip-side ellipsis is also in place. Found:\n$dialogSrc",
            REGEX_CUSTOM_RELATION_RAW_ASSIGN.containsMatchIn(dialogSrc),
        )
    }

    @Test
    fun `link chip bounds its width and ellipsizes its label`() {
        val src = readPersonLinksRowSource()
        assertNotNull("PersonLinksRow.kt must be readable off disk at $PERSON_LINKS_ROW_PATH", src)
        val text = src!!

        val rowBody = findComposableBodyRange(text, "fun PersonLinksRow(")
        assertNotNull("could not locate the PersonLinksRow composable body", rowBody)
        val rowSrc = text.substring(rowBody!!.first, rowBody.second)

        val chipRange = findCallBodyRange(rowSrc, callName = "AssistChip", afterMarker = "onOpenPerson(link.targetId)")
        assertNotNull("could not locate the link AssistChip in PersonLinksRow's body", chipRange)
        val chipSrc = rowSrc.substring(chipRange!!.first, chipRange.second)

        assertTrue(
            "the link AssistChip must bound its own width (e.g. Modifier.widthIn(max = ...)) -- " +
                "without a width bound, a LazyRow item is measured with unbounded main-axis " +
                "width, so maxLines/TextOverflow on the label below can never actually trigger. " +
                "Found:\n$chipSrc",
            REGEX_WIDTH_BOUND.containsMatchIn(chipSrc),
        )
        assertTrue(
            "the link chip's label Text must set maxLines = 1, so an overlong relation string " +
                "is forced onto a single line instead of silently growing the chip. Found:\n$chipSrc",
            REGEX_MAX_LINES_ONE.containsMatchIn(chipSrc),
        )
        assertTrue(
            "the link chip's label Text must set overflow = TextOverflow.Ellipsis, so an " +
                "overlong relation is visibly truncated with '...' instead of being silently " +
                "clipped off-screen with no indication more text exists. Found:\n$chipSrc",
            REGEX_ELLIPSIS.containsMatchIn(chipSrc),
        )
    }

    private fun readPersonLinksRowSource(): String? {
        val candidates = listOf(
            File(PERSON_LINKS_ROW_PATH),
            File("app/$PERSON_LINKS_ROW_PATH"),
        )
        for (f in candidates) {
            if (f.exists()) return f.readText(Charsets.UTF_8)
        }
        return null
    }

    /**
     * Finds a top-level (or private top-level) function's body range:
     * `signature` through the matching close of its trailing `{ ... }`
     * body. Walks the parameter list's *parens* to their balanced
     * close first, then finds the body `{` after that -- safe for
     * signatures whose parameter defaults contain braces (not the
     * case here, but keeps this helper consistent with the same one
     * in [com.kaavalan.note.features.capture.NoteBarTest]).
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
     * Finds the full extent of a `callName(...) { ... }` call -- from
     * `callName(` through the matching close of its *trailing content
     * lambda* -- whose combined argument-list + body is the first to
     * reference [afterMarker]. Used here to isolate the link
     * `AssistChip(...)` (identified by its `onClick` body) from the
     * dialog's other `AssistChip` calls (default relations, person
     * picker) that live in a different composable entirely.
     */
    private fun findCallBodyRange(text: String, callName: String, afterMarker: String): Pair<Int, Int>? {
        var searchFrom = 0
        val prefix = "$callName("
        while (true) {
            val callIdx = text.indexOf(prefix, searchFrom)
            if (callIdx < 0) return null
            val argsOpen = callIdx + callName.length
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
            val fullRange = callIdx to (argsClose + 1)
            if (text.substring(callIdx, argsClose + 1).contains(afterMarker)) {
                return fullRange
            }
            searchFrom = argsClose + 1
        }
    }

    private companion object {
        const val PERSON_LINKS_ROW_PATH =
            "src/main/java/com/kaavalan/note/ui/home/PersonLinksRow.kt"

        val REGEX_CUSTOM_RELATION_TAKE = Regex(
            """onValueChange\s*=\s*\{\s*customRelation\s*=\s*it\.take\(""",
        )
        val REGEX_CUSTOM_RELATION_RAW_ASSIGN = Regex(
            """onValueChange\s*=\s*\{\s*customRelation\s*=\s*it\s*\}""",
        )
        val REGEX_WIDTH_BOUND = Regex(
            """modifier\s*=\s*Modifier\.widthIn\(\s*max\s*=""",
        )
        val REGEX_MAX_LINES_ONE = Regex(
            """maxLines\s*=\s*1""",
        )
        val REGEX_ELLIPSIS = Regex(
            """overflow\s*=\s*TextOverflow\.Ellipsis""",
        )
    }
}
