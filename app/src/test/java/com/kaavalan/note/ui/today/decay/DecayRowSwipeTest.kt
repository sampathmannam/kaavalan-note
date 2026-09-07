package com.kaavalan.note.ui.today.decay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.9.5 (Compact DecayRow): the per-row "Mark recent" TextButton
 * is gone. The affordance moved to:
 *  1. Swipe-right past a 96dp threshold (Material 3 standard
 *     list-item side-effect action).
 *  2. Long-press → ModalBottomSheet with "Mark as recent" +
 *     "Cancel".
 *
 * This test is a **static scan** of `DecaySection.kt`. The pattern
 * is the same as
 * [com.kaavalan.note.ui.home.HomeScreenTest]: Robolectric 4.13's
 * launcher-activity resolution (PR #4736) makes
 * `createComposeRule()` / `createAndroidComposeRule<ComponentActivity>()`
 * fail in the unit-test classpath regardless of how the test
 * manifest is shaped. The static-scan approach is more durable
 * and catches the same class of regression (any refactor that
 * drops the swipe gesture wiring or the long-press sheet fails
 * the build).
 *
 * The 8 assertions below cover the structural contract that
 * `DecayRow` now satisfies:
 *  - The Mark recent TextButton is gone.
 *  - The ReachOutPill is the only right-side control.
 *  - The Card is `combinedClickable` with both `onClick` and
 *    `onLongClick`.
 *  - The long-press opens a ModalBottomSheet (state-gated).
 *  - The swipe gesture is wired via `pointerInput` +
 *    `detectHorizontalDragGestures`.
 *  - The swipe threshold is 96dp.
 *  - The swipe past the threshold fires `onMarkRecent`.
 *  - The "Mark as recent" text appears in the action sheet
 *    body (drives the action button).
 */
class DecayRowSwipeTest {

    private val decaySectionFile: File =
        File("src/main/java/com/kaavalan/note/ui/today/decay/DecaySection.kt").absoluteFile

    private fun source(): String = decaySectionFile.readText(Charsets.UTF_8)

    /**
     * Locate the body of the private `DecayRow` composable so the
     * assertions check the per-row rendering specifically, not
     * the whole `DecaySection.kt` (which includes the
     * `RedistributeDialog`'s TextButtons for "Confirm"/"Cancel" —
     * the latter would false-positive a TextButton scan if we
     * used file-level matching).
     */
    private fun decayRowBody(text: String): String? {
        val sig = "private fun DecayRow("
        val start = text.indexOf(sig)
        if (start < 0) return null
        // Walk forward through `{` braces to find the matching
        // close. This is a deliberately-simple scanner because
        // Kotlin's grammar doesn't allow `{` inside the parameter
        // list for a top-level `fun`.
        var depth = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
            i++
        }
        return null
    }

    /**
     * v1.9.5 (1/8): the per-row `Mark recent` TextButton is gone.
     * The v1.9.4 layout rendered a `TextButton(onClick = onMarkRecent, ...)`
     * next to the ReachOutPill; the v1.9.5 layout moves the
     * affordance to swipe-right + long-press. The button text
     * "Mark recent" still appears (in the long-press action sheet
     * and the swipe background), so the test checks the
     * TextButton wiring, not the string.
     */
    @Test
    fun `v1_9_5 mark recent TextButton is removed from DecayRow`() {
        val text = source()
        val body = decayRowBody(text)
        assertNotNull(
            "Could not locate private fun DecayRow(...) in DecaySection.kt",
            body,
        )
        assertFalse(
            "DecayRow must NOT contain a `TextButton(onClick = onMarkRecent, ...)` " +
                "call site. The Mark recent affordance moved to swipe-right + long-press.",
            body!!.contains(Regex("""TextButton\s*\(\s*[^)]*onClick\s*=\s*onMarkRecent""")) ||
                body.contains("TextButton(\n") && body.contains("onClick = onMarkRecent"),
        )
    }

    /**
     * v1.9.5 (2/8): the Card is `combinedClickable` (replaces
     * the v1.9.4 `clickable` so we can wire `onLongClick` in
     * addition to `onClick`).
     */
    @Test
    fun `v1_9_5 DecayRow Card is combinedClickable with onLongClick`() {
        val text = source()
        val body = decayRowBody(text)!!
        assertTrue(
            "DecayRow Card must use combinedClickable (not clickable) so the " +
                "long-press can open the action sheet.",
            body.contains(".combinedClickable("),
        )
        assertTrue(
            "DecayRow combinedClickable must wire onLongClick = { showActionSheet = true }",
            Regex("""onLongClick\s*=\s*\{\s*showActionSheet\s*=\s*true\s*\}""").containsMatchIn(body),
        )
    }

    /**
     * v1.9.5 (3/8): the long-press opens a ModalBottomSheet.
     * The sheet is rendered when `showActionSheet == true`
     * (state-gated, not always-on).
     */
    @Test
    fun `v1_9_5 long-press opens a state-gated ModalBottomSheet`() {
        val text = source()
        val body = decayRowBody(text)!!
        assertTrue(
            "DecayRow must render a ModalBottomSheet for the long-press action.",
            body.contains("ModalBottomSheet("),
        )
        assertTrue(
            "The ModalBottomSheet must be gated on the showActionSheet state.",
            body.contains("if (showActionSheet) {"),
        )
        assertTrue(
            "The ModalBottomSheet must wire onDismissRequest = { showActionSheet = false }",
            body.contains("onDismissRequest = { showActionSheet = false }"),
        )
    }

    /**
     * v1.9.5 (4/8): the swipe-right gesture is wired through
     * `pointerInput` + `detectHorizontalDragGestures`. The
     * `onDragEnd` branch fires `onMarkRecent()` when the
     * offset exceeds the threshold.
     */
    @Test
    fun `v1_9_5 DecayRow has a swipe-right detectHorizontalDragGestures gesture`() {
        val text = source()
        val body = decayRowBody(text)!!
        assertTrue(
            "DecayRow must host a pointerInput { detectHorizontalDragGestures } block " +
                "for the swipe-right gesture.",
            body.contains(".pointerInput(") &&
                body.contains("detectHorizontalDragGestures("),
        )
        assertTrue(
            "The onDragEnd branch of detectHorizontalDragGestures must fire " +
                "onMarkRecent() once the offset exceeds the threshold.",
            Regex(
                """onDragEnd\s*=\s*\{[\s\S]{0,200}onMarkRecent\s*\(\s*\)""",
            ).containsMatchIn(body),
        )
    }

    /**
     * v1.9.5 (5/8): the swipe threshold is 96dp. Material 3
     * standard threshold for list-item side-effect actions.
     */
    @Test
    fun `v1_9_5 swipe threshold is 96dp`() {
        val text = source()
        val body = decayRowBody(text)!!
        // The plan spec'd the threshold as 96dp. The
        // implementation reads it from `SWIPE_THRESHOLD_DP` and
        // applies `toPx()` via LocalDensity. The constant must
        // be 96.
        assertTrue(
            "DecayRow must reference a 96dp swipe threshold (via SWIPE_THRESHOLD_DP or " +
                "an inline literal).",
            body.contains("SWIPE_THRESHOLD_DP") || body.contains("96.dp"),
        )
        // And the file-level constant must equal 96.
        assertTrue(
            "DecaySection.kt must declare `SWIPE_THRESHOLD_DP = 96` (or the inline " +
                "threshold must be 96dp).",
            text.contains("SWIPE_THRESHOLD_DP = 96"),
        )
    }

    /**
     * v1.9.5 (6/8): the `ReachOutPill(row.status)` is still the
     * only right-side control. The v1.9.4 `TextButton` +
     * `Spacer` siblings are gone. (The TextButton scan in test
     * #1 covers the same surface from the other side; this one
     * asserts the pill is still present.)
     */
    @Test
    fun `v1_9_5 ReachOutPill is the only right-side control in DecayRow`() {
        val text = source()
        val body = decayRowBody(text)!!
        assertTrue(
            "DecayRow must still render the ReachOutPill(row.status) as the right-side " +
                "status indicator.",
            body.contains("ReachOutPill(row.status)"),
        )
    }

    /**
     * v1.9.5 (7/8): the swipe background label is the
     * "Mark recent" text. The user sees this label as they drag
     * a card right; releasing past the 96dp threshold fires the
     * action.
     */
    @Test
    fun `v1_9_5 swipe background shows Mark recent label`() {
        val text = source()
        val body = decayRowBody(text)!!
        // The background Box with the tertiaryContainer colour
        // and the `decay_mark_recent` text. The string resource
        // is the same one the v1.9.4 TextButton used; we just
        // check the resource reference is in the swipe
        // background area, not the per-row TextButton.
        assertTrue(
            "DecayRow swipe background must reference R.string.decay_mark_recent " +
                "for the visible label.",
            body.contains("R.string.decay_mark_recent"),
        )
    }

    /**
     * v1.9.5 (8/8): the days-quiet text cap stays as a safety
     * net (`maxLines = 1` + ellipsis) so a 365d count doesn't
     * wrap and push the card back to v1.9.3 height. The
     * `maxLines = 1` is what makes the layout deterministic
     * regardless of the day count; the removal of the
     * TextButton gives the column the width to render
     * "haven't touched in 93 days" without ellipsis on a
     * 1080px device.
     */
    @Test
    fun `v1_9_5 days-quiet text keeps maxLines equals 1 with ellipsis safety net`() {
        val text = source()
        val body = decayRowBody(text)!!
        // The pluralStringResource(R.plurals.decay_days_quiet, ...) Text must
        // still have maxLines = 1 + TextOverflow.Ellipsis.
        // Find the text block by anchoring on the plurals
        // resource call and walking forward to the next
        // `Text(` call.
        val pluralsIdx = body.indexOf("R.plurals.decay_days_quiet")
        assertTrue(
            "DecayRow must render the days-quiet pluralised text.",
            pluralsIdx >= 0,
        )
        val tail = body.substring(pluralsIdx, minOf(pluralsIdx + 2000, body.length))
        assertTrue(
            "The days-quiet Text must keep maxLines = 1 as a safety net for very long " +
                "day counts (e.g. 365d).",
            Regex("""maxLines\s*=\s*1""").containsMatchIn(tail),
        )
        assertTrue(
            "The days-quiet Text must keep TextOverflow.Ellipsis as a safety net.",
            tail.contains("TextOverflow.Ellipsis"),
        )
    }

    /**
     * From the character index of an opening bracket (`(` or
     * `{`), walks forward tracking nesting depth across BOTH
     * bracket kinds and returns the index of the bracket that
     * brings the depth back to zero — i.e. the bracket that
     * actually matches the one at [openIndex]. Kotlin's grammar
     * guarantees `(`/`{` pairs are properly nested relative to
     * each other, so a single combined depth counter correctly
     * finds the matching close even though a call's argument
     * list (`(...)`) routinely contains lambda arguments
     * (`{...}`), as every modifier chain here does.
     */
    private fun matchingClose(text: String, openIndex: Int): Int {
        var depth = 0
        var i = openIndex
        while (i < text.length) {
            when (text[i]) {
                '(', '{' -> depth++
                ')', '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /**
     * Returns the full `Card(...)` call — from the opening `(`
     * of the call through its matching `)` — i.e. exactly the
     * modifier chain and other named arguments passed to
     * `Card`, WITHOUT the trailing content lambda. Used to
     * inspect the Card's own modifier chain in isolation from
     * everything the card renders inside it.
     */
    private fun cardCallArgs(body: String): String {
        val sig = "Card("
        val start = body.indexOf(sig)
        assertTrue("Could not locate a Card(...) call in DecayRow.", start >= 0)
        val openParen = start + sig.length - 1
        val closeParen = matchingClose(body, openParen)
        assertTrue("Card(...) call in DecayRow has unbalanced brackets.", closeParen >= 0)
        return body.substring(openParen, closeParen + 1)
    }

    /**
     * v2.1.3 (adversarial-QA fix): regression test for a
     * confirmed-on-device bug where swipe-right-to-mark-recent
     * never fired. The `pointerInput { detectHorizontalDragGestures }`
     * modifier used to live on the Box that WRAPPED the Card
     * (an ancestor of the `combinedClickable` Card, not the
     * same node). Two independent low-level gesture recognizers
     * split across a parent/child boundary competed for the
     * same touch stream: on the `Main` pointer-event pass the
     * deeper node — the Card's `combinedClickable` — is
     * dispatched to first and its own press tracking consumed
     * the horizontal position-change deltas before the
     * ancestor's touch-slop detector ever saw them, so the 96dp
     * swipe threshold was never reached. On-device verification
     * confirmed tap and long-press (both owned entirely by
     * `combinedClickable`) kept working while an over-threshold
     * swipe produced no removal and no Undo snackbar.
     *
     * The fix moves the drag `pointerInput` onto the SAME Card
     * node as `combinedClickable`, chained immediately after it.
     * This test locks down both halves of that structural fix
     * so neither can silently regress:
     *  1. The drag `pointerInput` + `detectHorizontalDragGestures`
     *     must be part of the Card's own modifier chain (not a
     *     separate ancestor `Box`).
     *  2. Within that chain, `.combinedClickable(` must appear
     *     BEFORE `.pointerInput(` — that ordering is what makes
     *     the drag detector the "inner" node so it gets first
     *     refusal on horizontal movement during the Main pass.
     *  3. No `Box(...)` in DecayRow carries `.pointerInput(` in
     *     its own modifier chain — i.e. the drag detector never
     *     moves back onto a wrapping Box.
     */
    @Test
    fun `v2_1_3 swipe pointerInput lives on the Card after combinedClickable, not on an ancestor Box`() {
        val text = source()
        val body = decayRowBody(text)!!
        val cardArgs = cardCallArgs(body)

        assertTrue(
            "The Card's own modifier chain must include combinedClickable.",
            cardArgs.contains(".combinedClickable("),
        )
        assertTrue(
            "The Card's own modifier chain must include the swipe pointerInput " +
                "(detectHorizontalDragGestures), not a separate ancestor Box.",
            cardArgs.contains(".pointerInput(") && cardArgs.contains("detectHorizontalDragGestures("),
        )

        val combinedClickableIdx = cardArgs.indexOf(".combinedClickable(")
        val pointerInputIdx = cardArgs.indexOf(".pointerInput(")
        assertTrue(
            "combinedClickable must be chained BEFORE the drag pointerInput on the " +
                "Card, so the drag detector is the 'inner' node and gets first refusal " +
                "on horizontal movement during the Main pointer-event pass. Found " +
                "combinedClickable at $combinedClickableIdx, pointerInput at $pointerInputIdx.",
            combinedClickableIdx in 0 until pointerInputIdx,
        )

        // Walk every Box(...) call in DecayRow and assert none of
        // them carry the drag pointerInput in their OWN modifier
        // chain — that would reintroduce the ancestor/descendant
        // split that caused the original bug.
        var searchFrom = 0
        var boxCallsChecked = 0
        while (true) {
            val boxStart = body.indexOf("Box(", searchFrom)
            if (boxStart < 0) break
            val openParen = boxStart + "Box(".length - 1
            val closeParen = matchingClose(body, openParen)
            assertTrue("A Box(...) call in DecayRow has unbalanced brackets.", closeParen >= 0)
            val boxArgs = body.substring(openParen, closeParen + 1)
            assertFalse(
                "Found a Box(...) whose own modifier chain carries the swipe " +
                    "pointerInput — the drag detector must live on the Card " +
                    "(chained after combinedClickable), not on a wrapping Box: " +
                    boxArgs.take(120),
                boxArgs.contains(".pointerInput("),
            )
            boxCallsChecked++
            searchFrom = closeParen + 1
        }
        assertTrue(
            "Expected to find at least the two wrapping Box(...) calls in DecayRow.",
            boxCallsChecked >= 2,
        )
    }
}
