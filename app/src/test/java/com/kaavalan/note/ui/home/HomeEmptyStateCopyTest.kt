package com.kaavalan.note.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for a real, adversarial-QA-found bug in the
 * empty Home screen ("No one yet") shown to every brand-new user
 * immediately after they skip or finish onboarding -- the very
 * first screen state a fresh install ever renders. [HomeScreen]'s
 * empty-state block reads `home_empty_subtitle` via
 * `stringResource`.
 *
 * **The finding.** `home_empty_subtitle` in `strings.xml` had a
 * literal U+FFFD Unicode replacement character (confirmed via
 * `hexdump`: byte sequence `ef bf bd`) standing in for what was
 * almost certainly meant to be an em dash. Every brand-new user
 * saw: "Add the first person you coordinate with <20> your SP,
 * your SHOs, anyone you give or take instructions from." with a
 * visible tofu/replacement glyph, on the very first screen they
 * ever see. This is the same lossy-encoding corruption class
 * already found and fixed once before in `bulk_snooze_banner`
 * (see [com.kaavalan.note.ui.today.decay.BulkSnoozeBannerCopyTest]),
 * except that fix only restored that one string -- a repo-wide
 * scan for the identical `ef bf bd` byte sequence turned up 20
 * total corrupted string resources still live in `strings.xml`.
 * All 20 are restored together in this fix. The second test below
 * asserts the general structural invariant (no `<string>` or
 * `<plurals><item>` value anywhere in the resource file may
 * contain a literal U+FFFD) so a future re-corruption of any of
 * the 20, or the same bug class landing on a new string, fails a
 * fast, deterministic unit test instead of shipping silently.
 *
 * **Why a static copy scan instead of a Compose UI test.** There's
 * no Compose test for Home-screen-level UI in this repo:
 * [com.kaavalan.note.ui.home.HomeScreenTest] and
 * [com.kaavalan.note.features.capture.NoteBarTest] document that
 * Robolectric can't drive real Compose interaction in this
 * project's unit-test classpath. [com.kaavalan.note.ui.privacy.ThreatModelCopyTest],
 * [com.kaavalan.note.features.onboarding.OnboardingCopyTest], and
 * [com.kaavalan.note.ui.today.decay.BulkSnoozeBannerCopyTest]
 * establish the fallback for pure-copy bugs: exercise the string
 * content directly via `strings.xml` file reads. This test follows
 * that same pattern.
 */
class HomeEmptyStateCopyTest {

    private val stringsFile: File =
        File("src/main/res/values/strings.xml").absoluteFile

    private fun source(): String = stringsFile.readText(Charsets.UTF_8)

    @Test
    fun `strings xml file is at the expected path`() {
        assertTrue(
            "strings.xml must exist at ${stringsFile.absolutePath}",
            stringsFile.exists(),
        )
    }

    @Test
    fun `home_empty_subtitle has no Unicode replacement character and reads as an em-dash-separated sentence`() {
        val value = Regex(
            "<string name=\"home_empty_subtitle\">(.*?)</string>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(source())?.groupValues?.get(1)
        assertTrue("home_empty_subtitle must be declared in strings.xml", value != null)
        assertFalse(
            "home_empty_subtitle contains a literal U+FFFD Unicode replacement character -- " +
                "this is the exact adversarial-QA-found bug that made the empty Home screen's " +
                "subtitle (the very first screen a brand-new user sees) render a visible " +
                "tofu/replacement glyph in place of an em dash. Found:\n$value",
            value!!.contains('�'),
        )
        assertTrue(
            "home_empty_subtitle must read 'Add the first person you coordinate with — your " +
                "SP, your SHOs, anyone you give or take instructions from.'. Found:\n$value",
            value == "Add the first person you coordinate with — your SP, your SHOs, anyone you give or take instructions from.",
        )
    }

    @Test
    fun `no string or plural item resource in strings xml contains a Unicode replacement character`() {
        val text = source()
        val offenders = mutableListOf<String>()

        Regex(
            "<string name=\"([^\"]+)\"[^>]*>(.*?)</string>",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(text).forEach { match ->
            val (name, value) = match.destructured
            if (value.contains('�')) offenders += "string/$name"
        }

        Regex(
            "<plurals name=\"([^\"]+)\">(.*?)</plurals>",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(text).forEach { pluralMatch ->
            val (pluralsName, block) = pluralMatch.destructured
            Regex(
                "<item quantity=\"([a-z]+)\">(.*?)</item>",
                RegexOption.DOT_MATCHES_ALL,
            ).findAll(block).forEach { itemMatch ->
                val (quantity, value) = itemMatch.destructured
                if (value.contains('�')) offenders += "plurals/$pluralsName[$quantity]"
            }
        }

        assertTrue(
            "The following string/plural resources in strings.xml contain a literal U+FFFD " +
                "Unicode replacement character (byte sequence `ef bf bd`) -- this is the same " +
                "lossy-encoding corruption class as the home_empty_subtitle and " +
                "bulk_snooze_banner bugs, and renders as a visible tofu/replacement glyph to " +
                "users. Every user-facing string resource must be free of it. Offenders: " +
                offenders.joinToString(", "),
            offenders.isEmpty(),
        )
    }
}
