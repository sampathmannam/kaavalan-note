package com.kaavalan.note.ui.today.decay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for a real, adversarial-QA-found bug in the
 * Today → DecaySection redistribute banner (the `AssistChip`
 * shown above the decay list once the quiet pile exceeds 5,
 * wired in [DecaySection]).
 *
 * **The finding.** The `bulk_snooze_banner` `<plurals>` block
 * in `strings.xml` had a literal U+FFFD Unicode replacement
 * character (confirmed via `hexdump`: byte sequence `ef bf bd`)
 * in place of the em dash separator, in both the `one` and
 * `other` quantity items. Every user with more than 5 quiet
 * contacts saw the banner render as e.g. "26 quiet contacts
 * <20> redistribute?" with a visible tofu/replacement glyph.
 * This is very likely mojibake from a lossy encoding step: the
 * same `ef bf bd` byte sequence is scattered throughout
 * `strings.xml` standing in for em dashes and ellipses
 * elsewhere (out of scope for this fix, which only restores
 * `bulk_snooze_banner`).
 *
 * **Why a static copy scan instead of a Compose UI test.**
 * There's no Compose test for banner-level UI in this repo:
 * [com.kaavalan.note.ui.home.HomeScreenTest] and
 * [com.kaavalan.note.features.capture.NoteBarTest] document
 * that Robolectric can't drive real Compose interaction in
 * this project's unit-test classpath. [ThreatModelCopyTest]
 * and [OnboardingCopyTest] establish the fallback for
 * pure-copy bugs: exercise the string content directly via
 * `strings.xml` file reads. This test follows that same
 * pattern: it asserts the structural invariant that caused the
 * bug (no Unicode replacement character in the resource) so a
 * future re-corruption of this string, or of the same bug
 * class landing in a sibling string, fails a fast, deterministic
 * unit test instead of shipping silently.
 */
class BulkSnoozeBannerCopyTest {

    private val stringsFile: File =
        File("src/main/res/values/strings.xml").absoluteFile

    private fun source(): String = stringsFile.readText(Charsets.UTF_8)

    private fun pluralItems(text: String, pluralsName: String): Map<String, String> {
        val block = Regex(
            "<plurals name=\"" + Regex.escape(pluralsName) + "\">(.*?)</plurals>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(text)?.groupValues?.get(1)
        assertNotNull("<plurals name=\"$pluralsName\"> must be declared in strings.xml", block)
        return Regex("<item quantity=\"([a-z]+)\">(.*?)</item>")
            .findAll(block!!)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `strings xml file is at the expected path`() {
        assertTrue(
            "strings.xml must exist at ${stringsFile.absolutePath}",
            stringsFile.exists(),
        )
    }

    @Test
    fun `bulk_snooze_banner has no Unicode replacement character`() {
        val items = pluralItems(source(), "bulk_snooze_banner")
        assertTrue("bulk_snooze_banner must declare 'one' and 'other' quantities", items.size == 2)
        items.forEach { (quantity, value) ->
            assertFalse(
                "bulk_snooze_banner's '$quantity' item contains a literal U+FFFD Unicode " +
                    "replacement character -- this is the exact adversarial-QA-found bug " +
                    "that made the Today redistribute banner render as 'N quiet contact(s) " +
                    "� redistribute?' to every user with more than 5 quiet contacts. " +
                    "Found:\n$value",
                value.contains('�'),
            )
        }
    }

    @Test
    fun `bulk_snooze_banner reads as an em-dash-separated question`() {
        val items = pluralItems(source(), "bulk_snooze_banner")
        assertTrue(
            "bulk_snooze_banner 'one' item must read '%1\$d quiet contact — redistribute?'. " +
                "Found:\n${items["one"]}",
            items["one"] == "%1\$d quiet contact — redistribute?",
        )
        assertTrue(
            "bulk_snooze_banner 'other' item must read '%1\$d quiet contacts — redistribute?'. " +
                "Found:\n${items["other"]}",
            items["other"] == "%1\$d quiet contacts — redistribute?",
        )
    }
}
