package com.kaavalan.note.features.capture

import com.kaavalan.note.data.instructions.MentionAndTagParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * v2.x (product decision, adversarial-QA follow-up): pins the
 * @mention entry point into the hierarchy dispatch flow.
 *
 * **The finding.** The whole dispatch feature
 * (`ui/hierarchy/DispatchComposerSheet` + `AudiencePickerSheet` +
 * `DispatchSheet`) shipped in v2.1.1 with ZERO call sites anywhere in
 * `app/src/main` -- no FAB, no menu item, nothing ever constructed it,
 * so it was unreachable in the shipped app despite the release notes
 * claiming otherwise. [MentionAndTagParser] was dead for the same
 * reason: nothing outside its own file ever called `parse`.
 *
 * **The decided fix.** The note bar is the entry point: typing an
 * audience-shaped mention offers to turn the note into a dispatch.
 * That keeps the app's "one primary input" rule (the NoteBar is
 * deliberately the single capture affordance) instead of adding a
 * competing compose button, and it gives the already-built mention
 * parser its intended job.
 *
 * **Two invariants this pins:**
 *  1. The suggestion fires for audience-shaped mentions
 *     (designation / station / all) -- the three
 *     [MentionAndTagParser.Mention.Prefix] values that map onto a
 *     dispatch audience.
 *  2. It does NOT fire for `Prefix.NAME`. "spoke to @ramesh about the
 *     seizure case" is ordinary prose; offering to broadcast on every
 *     colleague's name would be noise, and a single named person is
 *     already served by Person Detail's "Add instruction for X".
 *
 * The detection is a private function on [CaptureViewModel], and that
 * ViewModel needs a SavedStateHandle + four repositories to
 * construct. Following the same static-source-scan pattern
 * [NoteBarTest] and [com.kaavalan.note.ui.home.HomeScreenTest]
 * established for this project (Robolectric 4.13 can't drive real
 * Compose here), this asserts the parser-level contract the detection
 * is built on, plus the structural wiring that routes it into the UI.
 */
class DispatchSuggestionTest {

    private fun firstAudienceMention(text: String): MentionAndTagParser.Mention? =
        MentionAndTagParser.parse(text).mentions.firstOrNull { m ->
            m.prefix == MentionAndTagParser.Mention.Prefix.DESIGNATION ||
                m.prefix == MentionAndTagParser.Mention.Prefix.STATION ||
                m.prefix == MentionAndTagParser.Mention.Prefix.ALL
        }

    @Test
    fun `a designation mention is audience-shaped and should offer dispatch`() {
        val m = firstAudienceMention("@si brief the SHO before the magistrate visit")
        assertNotNull("@si must be recognised as a designation audience", m)
        assertEquals(MentionAndTagParser.Mention.Prefix.DESIGNATION, m!!.prefix)
    }

    @Test
    fun `a station mention is audience-shaped and should offer dispatch`() {
        val m = firstAudienceMention("@station:Subedari bandobast at the weekly market")
        assertNotNull("@station:X must be recognised as a station audience", m)
        assertEquals(MentionAndTagParser.Mention.Prefix.STATION, m!!.prefix)
    }

    @Test
    fun `an all mention is audience-shaped and should offer dispatch`() {
        val m = firstAudienceMention("@all report in by 1800 hours")
        assertNotNull("@all must be recognised as a broadcast audience", m)
        assertEquals(MentionAndTagParser.Mention.Prefix.ALL, m!!.prefix)
    }

    @Test
    fun `a plain person mention must NOT offer dispatch`() {
        // The exact false-positive this guards: an ordinary note that
        // happens to name a colleague must stay an ordinary note.
        assertNull(
            "a bare @name is prose, not an audience -- offering to broadcast here would be noise",
            firstAudienceMention("spoke to @ramesh about the seizure case"),
        )
    }

    @Test
    fun `text with no mention at all offers nothing`() {
        assertNull(firstAudienceMention("confirm the IO for the 7-car seizure case"))
        assertNull("a bare @ with nothing after it is not yet a mention", firstAudienceMention("@"))
    }

    @Test
    fun `CaptureViewModel routes the audience mention into a dispatchSuggestion`() {
        val src = readSource("src/main/java/com/kaavalan/note/features/capture/CaptureViewModel.kt")
        assertNotNull("CaptureViewModel.kt must be readable off disk", src)
        // onTextChanged is the only per-keystroke hook; the detection
        // has to hang off it or the suggestion never appears while typing.
        assertTrue(
            "onTextChanged must populate dispatchSuggestion so the offer tracks what is typed",
            Regex("""fun onTextChanged[\s\S]{0,400}?dispatchSuggestion\s*=""").containsMatchIn(src!!),
        )
        assertTrue(
            "detection must exclude Prefix.NAME -- see the false-positive test above",
            src.contains("Prefix.DESIGNATION") && src.contains("Prefix.STATION") && src.contains("Prefix.ALL") &&
                !Regex("""m\.prefix\s*==\s*MentionAndTagParser\.Mention\.Prefix\.NAME""").containsMatchIn(src),
        )
    }

    @Test
    fun `HomeScreen actually constructs DispatchComposerSheet`() {
        // The original bug was purely "nothing ever calls it". This is
        // the assertion that would have failed before the fix.
        val src = readSource("src/main/java/com/kaavalan/note/ui/home/HomeScreen.kt")
        assertNotNull("HomeScreen.kt must be readable off disk", src)
        assertTrue(
            "HomeScreen must construct DispatchComposerSheet -- without a live call site the " +
                "whole hierarchy dispatch feature is unreachable, which is the bug this fixes",
            src!!.contains("DispatchComposerSheet("),
        )
        assertTrue(
            "the composer must be seeded with the text carried over from the capture sheet",
            Regex("""DispatchComposerSheet\([\s\S]{0,200}?initialText\s*=""").containsMatchIn(src),
        )
    }

    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }

    private fun readSource(relativePath: String): String? {
        for (f in listOf(File(relativePath), File("app/$relativePath"))) {
            if (f.exists()) return f.readText(Charsets.UTF_8)
        }
        return null
    }
}
