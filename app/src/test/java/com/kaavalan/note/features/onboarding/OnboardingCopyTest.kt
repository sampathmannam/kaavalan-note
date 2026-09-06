package com.kaavalan.note.features.onboarding

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for a real, adversarial-QA-found bug on
 * onboarding page 2 ("Kaavalan note is not a notes app", the
 * v1.9.0 PROD-READINESS-P3-P0-#9 explainer wired into the
 * first-run pager in v1.9.1 -- see
 * [com.kaavalan.note.features.onboarding.OnboardingScreen]'s
 * `NotJustNotesPage`).
 *
 * **The finding.** `onboarding_screen_4_body` in `strings.xml`
 * was a copy-paste error: its entire "Free, forever" sentence
 * pair ("No ads, no premium tier, no tracking. The full source
 * is on GitHub if you want to read the threat model or audit
 * the crypto.") was duplicated verbatim, with a stray `..` at
 * the seam where the paste landed. A user reading onboarding
 * page 2 saw the same two sentences twice back to back.
 *
 * **Why a static copy scan instead of a Compose UI test.**
 * There's no Compose test for the full onboarding pager in this
 * repo: [com.kaavalan.note.ui.home.HomeScreenTest] documents
 * that Robolectric 4.13's launcher-activity resolution (PR
 * #4736) makes `createComposeRule()` /
 * `createAndroidComposeRule<ComponentActivity>()` fail in this
 * project's unit-test classpath regardless of manifest shape.
 * [com.kaavalan.note.ui.privacy.ThreatModelCopyTest] establishes
 * the fallback for pure-copy bugs: exercise the string content
 * directly via `strings.xml` file reads. This test follows that
 * same pattern, and generalizes the invariant (no onboarding
 * body sentence may repeat itself) across all four
 * `onboarding_screen_N_body` strings so a repeat of this exact
 * copy-paste bug class -- on any of the four pages -- fails
 * here instead of shipping.
 */
class OnboardingCopyTest {

    private val stringsFile: File =
        File("src/main/res/values/strings.xml").absoluteFile

    private fun source(): String = stringsFile.readText(Charsets.UTF_8)

    private val bodyKeys = listOf(
        "onboarding_screen_1_body",
        "onboarding_screen_2_body",
        "onboarding_screen_3_body",
        "onboarding_screen_4_body",
    )

    private fun stringValue(text: String, key: String): String? {
        val regex = Regex("<string name=\"" + Regex.escape(key) + "\">(.*?)</string>")
        return regex.find(text)?.groupValues?.get(1)
    }

    @Test
    fun `strings xml file is at the expected path`() {
        assertTrue(
            "strings.xml must exist at ${stringsFile.absolutePath}",
            stringsFile.exists(),
        )
    }

    @Test
    fun `onboarding_screen_4_body does not repeat the 'Free, forever' sentence`() {
        val body = stringValue(source(), "onboarding_screen_4_body")
        assertNotNull("onboarding_screen_4_body must be declared in strings.xml", body)
        assertTrue(
            "onboarding_screen_4_body must mention GitHub exactly once -- two mentions " +
                "means the 'No ads / GitHub' sentence pair got pasted in twice, the exact " +
                "bug this test pins. Found:\n$body",
            Regex("GitHub").findAll(body!!).count() == 1,
        )
        assertFalse(
            "onboarding_screen_4_body must not contain a stray double period ('..') -- " +
                "that was the seam left behind where the duplicated sentence was pasted " +
                "back in. Found:\n$body",
            body.contains(".."),
        )
    }

    @Test
    fun `no onboarding body string repeats one of its own sentences`() {
        val text = source()
        bodyKeys.forEach { key ->
            val body = stringValue(text, key)
            assertNotNull("$key must be declared in strings.xml", body)
            val sentences = body!!.split(". ")
                .map { it.trim().trimEnd('.') }
                .filter { it.isNotEmpty() }
            val duplicates = sentences.groupingBy { it }.eachCount().filter { it.value > 1 }
            assertTrue(
                "$key repeats a sentence verbatim, which is the copy-paste bug class " +
                    "pinned by this test -- duplicated: $duplicates. Full string:\n$body",
                duplicates.isEmpty(),
            )
        }
    }
}
