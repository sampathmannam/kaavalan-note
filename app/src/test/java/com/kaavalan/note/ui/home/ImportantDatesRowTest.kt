package com.kaavalan.note.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.0.x (BUG FIX regression guard, found via adversarial QA audit of
 * Person Detail > Important Dates): pins the fix for a real,
 * reproducible bug in [ImportantDatesRow]'s per-row delete action.
 *
 * **The finding.** Each existing important-date row rendered a
 * `TextButton` literally labelled "Cancel" (`stringResource(R.string.cancel)`
 * -- a copy-paste of the generic dismiss-label resource) whose
 * `onClick` called `viewModel.delete(d.id)` directly: an
 * irreversible delete, fired with a single tap, labelled with a word
 * that means the opposite of what it does. Every other destructive
 * flow on the very same [PersonDetailScreen] --
 * [DropDialog]/`action_drop`, [InstructionSensitiveDialog], and
 * [PersonSensitiveDialog] -- stages the target and only acts from an
 * `AlertDialog`'s confirm button. This row had no confirm step at
 * all.
 *
 * **The fix.** The row button is now labelled
 * `R.string.important_date_delete` ("Delete") and only stages the
 * row (`deleteTarget = d`); the actual `viewModel.delete(...)` call
 * moved into [DeleteImportantDateDialog]'s `confirmButton`, mirroring
 * `DropDialog`'s stage-then-confirm shape.
 *
 * **Why a static source scan instead of a Compose click test.**
 * [HomeScreenTest] and [com.kaavalan.note.features.capture.NoteBarTest]
 * document why: Robolectric 4.13's launcher-activity resolution
 * (PR #4736) makes `createComposeRule()` /
 * `createAndroidComposeRule<ComponentActivity>()` fail in this
 * project's unit-test classpath regardless of manifest shape. This
 * test follows the same established pattern: assert the *structure*
 * that determines whether a delete requires confirmation, rather
 * than driving a real click. A regression back to "the row's delete
 * button fires `viewModel.delete` straight from its own `onClick`"
 * fails this test the same way the original bug should have been
 * caught before it shipped.
 */
class ImportantDatesRowTest {

    @Test
    fun `row delete button stages the target instead of deleting directly`() {
        val text = readImportantDatesRowSource()
        assertNotNull("ImportantDatesRow.kt must be readable off disk at $FILE_PATH", text)

        val forEachBody = findForEachBodyRange(text!!, "dates.forEach")
        assertNotNull("could not locate the dates.forEach { d -> ... } block", forEachBody)
        val rowSrc = text.substring(forEachBody!!.first, forEachBody.second)

        assertFalse(
            "the per-row TextButton must NOT call viewModel.delete(...) directly from its " +
                "own onClick -- that was the bug: an irreversible delete with no confirm " +
                "step. It must instead stage the row (e.g. `deleteTarget = d`) and let a " +
                "confirm dialog perform the delete. Found:\n$rowSrc",
            REGEX_DIRECT_DELETE_CALL.containsMatchIn(rowSrc),
        )
        assertTrue(
            "the per-row TextButton's onClick must stage a delete target (e.g. " +
                "`onClick = { deleteTarget = d }`) rather than acting immediately. Found:\n$rowSrc",
            REGEX_STAGES_TARGET.containsMatchIn(rowSrc),
        )
        assertFalse(
            "the per-row delete button must NOT be labelled with the generic " +
                "R.string.cancel resource -- that is the misleading-label half of the bug " +
                "(a destructive action literally labelled \"Cancel\"). Found:\n$rowSrc",
            REGEX_CANCEL_LABEL.containsMatchIn(rowSrc),
        )
        assertTrue(
            "the per-row delete button must use a delete-specific label resource. Found:\n$rowSrc",
            REGEX_DELETE_LABEL.containsMatchIn(rowSrc),
        )
    }

    @Test
    fun `deleting an important date requires confirmation before the delete call fires`() {
        val text = readImportantDatesRowSource()
        assertNotNull("ImportantDatesRow.kt must be readable off disk at $FILE_PATH", text)

        val dialogBody = findComposableBodyRange(text!!, "private fun DeleteImportantDateDialog(")
        assertNotNull(
            "could not find a DeleteImportantDateDialog composable -- a delete-confirmation " +
                "dialog mirroring PersonDetailScreen's DropDialog must exist",
            dialogBody,
        )
        val dialogSrc = text.substring(dialogBody!!.first, dialogBody.second)

        assertTrue(
            "DeleteImportantDateDialog must render an AlertDialog", dialogSrc.contains("AlertDialog"),
        )

        val confirmButtonBody = findBlockBodyRange(dialogSrc, "confirmButton = {")
        assertNotNull(
            "DeleteImportantDateDialog must have a confirmButton block", confirmButtonBody,
        )
        val confirmSrc = dialogSrc.substring(confirmButtonBody!!.first, confirmButtonBody.second)
        assertTrue(
            "the actual delete call must live behind the dialog's confirmButton, not fire " +
                "on the row's own tap. Found confirmButton body:\n$confirmSrc",
            confirmSrc.contains("onConfirm"),
        )

        // The dialog itself must not call viewModel.delete directly either --
        // it must delegate through an onConfirm callback (mirrors DropDialog /
        // InstructionSensitiveDialog / PersonSensitiveDialog's shape), with the
        // real repository call staying in ImportantDatesRow's onConfirm lambda.
        assertFalse(
            "DeleteImportantDateDialog must not call the repository/viewModel directly -- " +
                "it should only invoke the onConfirm callback, keeping the same " +
                "stage-in-caller/confirm-in-dialog shape as DropDialog. Found:\n$dialogSrc",
            dialogSrc.contains("viewModel.delete"),
        )

        // And the caller must wire that onConfirm to the real delete call, gated
        // behind the staged deleteTarget.
        val callerBody = findComposableBodyRange(text, "fun ImportantDatesRow(")
        assertNotNull("could not locate the ImportantDatesRow composable body", callerBody)
        val callerSrc = text.substring(callerBody!!.first, callerBody.second)
        assertTrue(
            "ImportantDatesRow must invoke DeleteImportantDateDialog with an onConfirm that " +
                "performs viewModel.delete(...) only once the dialog confirms. Found:\n$callerSrc",
            REGEX_CALLER_WIRES_CONFIRM.containsMatchIn(callerSrc),
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
     * matching close of its trailing `{ ... }` body. Walks the
     * parameter list's *parens* to their balanced close first (so
     * default-value lambdas like `= {}` in the signature don't confuse
     * a naive first-`{` search), then finds the body `{` after that.
     * Same approach as [com.kaavalan.note.features.capture.NoteBarTest].
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

        val REGEX_DIRECT_DELETE_CALL = Regex(
            """TextButton\s*\(\s*onClick\s*=\s*\{\s*viewModel\.delete""",
        )
        val REGEX_STAGES_TARGET = Regex(
            """TextButton\s*\(\s*onClick\s*=\s*\{\s*deleteTarget\s*=\s*d\s*\}""",
        )
        val REGEX_CANCEL_LABEL = Regex(
            """TextButton\s*\(\s*onClick\s*=\s*\{\s*deleteTarget[\s\S]*?R\.string\.cancel""",
        )
        val REGEX_DELETE_LABEL = Regex(
            """R\.string\.important_date_delete\b""",
        )
        val REGEX_CALLER_WIRES_CONFIRM = Regex(
            """DeleteImportantDateDialog\s*\([\s\S]*?onConfirm\s*=\s*\{[\s\S]*?viewModel\.delete\(target\.id\)[\s\S]*?deleteTarget\s*=\s*null""",
        )
    }
}
