package com.kaavalan.note.ui.home

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.0.x (BUG FIX regression guard, found via adversarial QA audit of
 * Person Detail > Important Dates): pins the fix for a real,
 * reproducible tap-through bug distinct from the one
 * [ImportantDatesRowTest] pins.
 *
 * **The finding.** On Person Detail for a person who already has at
 * least one Important Date: tap "+" (Add date) -> fill it in -> then
 * rapid double-tap the Add dialog's "Save" button (~30ms apart). The
 * Add dialog's [AlertDialog] closes on the *next* frame after the
 * first tap flips `showAdd` to false — a Compose recomposition, not
 * something the first tap's own click handler waits on — so a second
 * physical tap only ~30ms later can already be routed to whatever
 * sits at that same screen coordinate on the now-revealed Important
 * Dates list underneath, instead of the (already gone) dialog. In two
 * independent reproductions that second tap landed on an *existing*
 * date row's "Delete" button, opening an unrequested
 * `Delete "<label>"? This cannot be undone.` confirmation for a row
 * the user never touched. No data loss resulted (the confirm-then-act
 * shape [ImportantDatesRowTest] pins means the row was never actually
 * deleted unless the user then also confirmed), but the unrequested
 * destructive prompt itself is the hazard.
 *
 * **The fix.** [ImportantDatesRow] tracks a `deleteGuardActive` flag,
 * armed via a `LaunchedEffect` for [POST_ADD_DIALOG_DELETE_GUARD_MS]
 * every time the Add dialog closes (via Save *or* Cancel/dismiss —
 * the same race applies to either exit), and disables every row's
 * Delete `TextButton` (`enabled = !deleteGuardActive`) for that
 * window. A stray tap-through lands on a disabled button and is a
 * no-op instead of staging a delete target. This mirrors the
 * `enabled = !state.working` guard-while-transitioning shape already
 * used by `VaultExportSheet`/`VaultImportSheet`'s passphrase dialogs,
 * keyed on "just closed a dialog" instead of "async work in flight".
 *
 * **Why a static source scan instead of a Compose click test.**
 * [HomeScreenTest] and [com.kaavalan.note.features.capture.NoteBarTest]
 * document why: Robolectric 4.13's launcher-activity resolution
 * (PR #4736) makes `createComposeRule()` /
 * `createAndroidComposeRule<ComponentActivity>()` fail in this
 * project's unit-test classpath regardless of manifest shape, so the
 * ~30ms double-tap race cannot be driven directly in a unit test
 * either way. This test follows the same established pattern as
 * [ImportantDatesRowTest]: assert the *structure* that determines
 * whether a stray tap right after the Add dialog closes can reach a
 * row's Delete button, rather than driving a real click. A regression
 * back to "the row's Delete button is unconditionally enabled" or
 * "the Add dialog's close paths don't arm the guard" fails this test
 * the same way the original bug should have been caught before it
 * shipped.
 */
class ImportantDatesRowDoubleTapGuardTest {

    @Test
    fun `per-row Delete button is disabled while the post-dialog guard is active`() {
        val text = readImportantDatesRowSource()
        assertNotNull("ImportantDatesRow.kt must be readable off disk at $FILE_PATH", text)

        val forEachBody = findForEachBodyRange(text!!, "dates.forEach")
        assertNotNull("could not locate the dates.forEach { d -> ... } block", forEachBody)
        val rowSrc = text.substring(forEachBody!!.first, forEachBody.second)

        assertTrue(
            "the per-row delete TextButton must be gated by a guard flag (e.g. " +
                "`enabled = !deleteGuardActive`) so a stray tap-through right after the " +
                "Add-date dialog closes cannot stage a delete. Found:\n$rowSrc",
            REGEX_DELETE_BUTTON_GUARDED.containsMatchIn(rowSrc),
        )
    }

    @Test
    fun `both Add-dialog close paths arm the delete guard`() {
        val text = readImportantDatesRowSource()
        assertNotNull("ImportantDatesRow.kt must be readable off disk at $FILE_PATH", text)

        val callerBody = findComposableBodyRange(text!!, "fun ImportantDatesRow(")
        assertNotNull("could not locate the ImportantDatesRow composable body", callerBody)
        val callerSrc = text.substring(callerBody!!.first, callerBody.second)

        // A guard that only resets on Save, or only on Cancel/dismiss, leaves the
        // other exit path just as exposed to the same race -- both must re-arm it.
        val addDialogCall = findCallArgsRange(callerSrc, "AddImportantDateDialog(")
        assertNotNull(
            "could not locate the AddImportantDateDialog(...) call in ImportantDatesRow",
            addDialogCall,
        )
        val addDialogSrc = callerSrc.substring(addDialogCall!!.first, addDialogCall.second)

        val onAddBody = findBlockBodyRange(addDialogSrc, "onAdd = {")
        assertNotNull("could not locate AddImportantDateDialog's onAdd lambda", onAddBody)
        val onAddSrc = addDialogSrc.substring(onAddBody!!.first, onAddBody.second)
        assertTrue(
            "the onAdd (Save) close path must also arm the post-dialog delete guard " +
                "(e.g. bump a nonce consumed by a LaunchedEffect), not just flip `showAdd`. " +
                "Found:\n$onAddSrc",
            REGEX_ARMS_GUARD.containsMatchIn(onAddSrc),
        )

        val onDismissBody = findBlockBodyRange(addDialogSrc, "onDismiss = {")
        assertNotNull("could not locate AddImportantDateDialog's onDismiss lambda", onDismissBody)
        val onDismissSrc = addDialogSrc.substring(onDismissBody!!.first, onDismissBody.second)
        assertTrue(
            "the onDismiss (Cancel / outside-tap) close path must also arm the same " +
                "post-dialog delete guard -- the tap-through race is identical regardless " +
                "of which button closed the dialog. Found:\n$onDismissSrc",
            REGEX_ARMS_GUARD.containsMatchIn(onDismissSrc),
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
     * Finds a top-level function's body range: `signature` through the
     * matching close of its trailing `{ ... }` body. Same approach as
     * [ImportantDatesRowTest] / [com.kaavalan.note.features.capture.NoteBarTest].
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
     * Finds the extent of a `<marker> { ... }` block (e.g.
     * `dates.forEach { d -> ... }`) -- from the `{` right after
     * [marker] through its matching close.
     */
    private fun findForEachBodyRange(text: String, marker: String): Pair<Int, Int>? {
        val markerIdx = text.indexOf(marker)
        if (markerIdx < 0) return null
        val openBrace = text.indexOf('{', markerIdx)
        if (openBrace < 0) return null
        return matchBrace(text, openBrace)
    }

    /** Finds the extent of a `<marker>{ ... }` block starting exactly at [marker]'s trailing `{`. */
    private fun findBlockBodyRange(text: String, marker: String): Pair<Int, Int>? {
        val markerIdx = text.indexOf(marker)
        if (markerIdx < 0) return null
        val openBrace = markerIdx + marker.length - 1 // marker ends in "{"
        if (openBrace < 0 || text[openBrace] != '{') return null
        return matchBrace(text, openBrace)
    }

    /**
     * Finds the extent of a `<marker>(...)` call's argument list --
     * from the `(` right after [marker] (which must end in `(`)
     * through its matching close paren, walking nested parens/braces
     * so a lambda argument containing its own `(` / `{` doesn't
     * terminate the scan early.
     */
    private fun findCallArgsRange(text: String, marker: String): Pair<Int, Int>? {
        val markerIdx = text.indexOf(marker)
        if (markerIdx < 0) return null
        val openParen = markerIdx + marker.length - 1
        if (openParen < 0 || text[openParen] != '(') return null
        var parenDepth = 0
        var braceDepth = 0
        var j = openParen
        while (j < text.length) {
            when (text[j]) {
                '(' -> parenDepth += 1
                ')' -> {
                    parenDepth -= 1
                    if (parenDepth == 0 && braceDepth == 0) return openParen to (j + 1)
                }
                '{' -> braceDepth += 1
                '}' -> braceDepth -= 1
            }
            j += 1
        }
        return null
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

        // Matches the per-row Delete TextButton's `enabled = !<guardFlag>` param,
        // wherever it appears relative to `onClick`, so this doesn't over-fit the
        // exact param order chosen by the fix.
        val REGEX_DELETE_BUTTON_GUARDED = Regex(
            """TextButton\s*\([\s\S]*?onClick\s*=\s*\{\s*deleteTarget\s*=\s*d\s*\}[\s\S]{0,80}?enabled\s*=\s*!\w+""",
        )

        // Matches a nonce bump / boolean flip that a LaunchedEffect could react to --
        // deliberately loose about the exact mechanism (nonce++ vs a timestamp vs a
        // direct flag flip) since the invariant under test is "this close path feeds
        // the guard", not one specific implementation of the guard's clock.
        val REGEX_ARMS_GUARD = Regex(
            """\b\w*[Gg]uard\w*\s*(\+\+|=)|\b\w*[Cc]losed\w*(Nonce|At\w*)\s*(\+\+|=)""",
        )
    }
}
