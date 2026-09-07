package com.kaavalan.note.ui.home

import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v1.4 (PHONE-FINDING-1): the empty-state "Add person" Button must be
 * present and tappable when the Home tab has zero people.
 *
 * The previous v1.3 path relied on a [androidx.compose.material3.FloatingActionButton]
 * whose `primaryContainer` colour was too low-contrast against the
 * dark surface — new users missed the entry point. The v1.4 fix
 * adds a prominent primary-coloured [androidx.compose.material3.Button]
 * under the empty-state copy; the app bar provides the persistent
 * add-person entry point once people exist.
 *
 * This test is a **static scan** rather than a Compose UI test.
 * The pattern is the same as
 * [com.kaavalan.note.ui.AccessibilityContentDescriptionTest]: Robolectric
 * 4.13's launcher-activity resolution (PR #4736) makes
 * `createComposeRule()` / `createAndroidComposeRule<ComponentActivity>()`
 * fail in the unit-test classpath regardless of how the test manifest
 * is shaped. The static-scan approach is more durable and catches
 * the same class of regression (any refactor that drops the
 * `onAddPersonClick` wiring on the empty state fails the build).
 *
 * The scan asserts:
 *  1. `HomeScreen` renders the empty state with the wiring
 *     `EmptyState(onAddPersonClick = { showAddPerson = true })`.
 *  2. The `EmptyState` Composable's body contains a
 *     `Button(onClick = onAddPersonClick, ...)` block.
 *  3. That Button's body renders a `Text(text = stringResource(R.string.home_add_person), ...)`.
 *  4. `CaptureSheet(...)` is not coupled to adding a person: a raw
 *     first note is valid and can be organised later.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = HiltTestApplication::class)
class HomeScreenTest {

    /**
     * (1) HomeScreen must render the empty state with the
     * `onAddPersonClick = { showAddPerson = true }` wiring. This
     * is the production button the empty state renders; without
     * the wiring the user has no path to AddPerson from the
     * empty state.
     */
    @Test
    fun homeScreen_emptyStateRendersAddPersonButton() {
        val src = readHomeScreenSource()
        assertNotNull(
            "HomeScreen.kt must be readable off disk at $HOME_SCREEN_PATH",
            src,
        )
        val text = src!!
        assertTrue(
            "HomeScreen must render the empty state with onAddPersonClick wiring:\n" +
                "    HomeUiState.Empty -> EmptyState(padding, onAddPersonClick = { showAddPerson = true })\n" +
                "but the source did not contain that line.",
            text.contains(REGEX_EMPTY_STATE_WIRING),
        )
    }

    /**
     * (2) The `EmptyState` Composable's body must include a
     * `Button(onClick = onAddPersonClick, ...)`. The test scans for
     * the call site rather than parsing the AST — a refactor that
     * renames the parameter to a different onClick lambda breaks
     * the build before it ships.
     */
    @Test
    fun emptyState_callsButtonWithOnAddPersonClick() {
        val text = readHomeScreenSource()!!
        // Find the EmptyState Composable body (the private fun EmptyState
        // declaration through to the next top-level @Composable or `}`).
        val emptyStateRange = findComposableBodyRange(
            text = text,
            signature = "private fun EmptyState(",
        )
        assertTrue(
            "Could not locate the private EmptyState Composable in HomeScreen.kt",
            emptyStateRange != null,
        )
        val body = text.substring(emptyStateRange!!.first, emptyStateRange.second)
        assertTrue(
            "EmptyState body must contain Button(onClick = onAddPersonClick, ...):\n" +
                "    Button(\n" +
                "        onClick = onAddPersonClick,\n" +
                "        ...\n" +
                "    ) { ... }\n" +
                "but the body was:\n$body",
            body.contains(REGEX_BUTTON_ON_ADD_PERSON_CLICK),
        )
    }

    /**
     * (3) The Button's body must render
     * `Text(text = stringResource(R.string.home_add_person), ...)`
     * so TalkBack and the visual layout both surface the "Add
     * person" label. The same string is also used by the People
     * header action, so the empty and populated states preserve
     * one clear people-management affordance.
     */
    @Test
    fun emptyState_buttonRendersHomeAddPersonText() {
        val text = readHomeScreenSource()!!
        val emptyStateRange = findComposableBodyRange(
            text = text,
            signature = "private fun EmptyState(",
        )!!
        val body = text.substring(emptyStateRange.first, emptyStateRange.second)
        assertTrue(
            "EmptyState body must contain Text(text = stringResource(R.string.home_add_person), ...)",
            body.contains(REGEX_BUTTON_HOME_ADD_PERSON_TEXT),
        )
    }

    /** A new user may save a raw note before creating a person. */
    @Test
    fun homeScreen_captureSheetDoesNotRequireAddPersonCallback() {
        val text = readHomeScreenSource()!!
        assertTrue(
            "HomeScreen must render CaptureSheet so a note can be captured from the People tab.",
            text.contains("CaptureSheet("),
        )
        assertTrue(
            "CaptureSheet must not require an add-person callback; first notes are allowed without a person.",
            !text.contains("onOpenAddPerson ="),
        )
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private fun readHomeScreenSource(): String? {
        val candidates = listOf(
            File(HOME_SCREEN_PATH),
            File("app/src/main/java/com/kaavalan/note/ui/home/HomeScreen.kt"),
        )
        for (f in candidates) {
            if (f.exists()) return f.readText(Charsets.UTF_8)
        }
        return null
    }

    /**
     * Find the byte range of a Composable's body — the opening
     * `{` after the function signature through the matching closing
     * `}`. A naive regex on the call sites is unreliable (the
     * trailing lambda body can span many lines). We do a manual
     * brace-count starting at the first `{` after the signature.
     */
    private fun findComposableBodyRange(
        text: String,
        signature: String,
    ): Pair<Int, Int>? {
        val sigIdx = text.indexOf(signature)
        if (sigIdx < 0) return null
        // Find the opening `{` of the function body.
        val openBrace = text.indexOf('{', sigIdx)
        if (openBrace < 0) return null
        var depth = 0
        var i = openBrace
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return openBrace to (i + 1)
                    }
                }
            }
            i += 1
        }
        return null
    }

    private companion object {
        // Tests run from the `app/` module directory under
        // gradle's working directory; the path is relative.
        const val HOME_SCREEN_PATH =
            "src/main/java/com/kaavalan/note/ui/home/HomeScreen.kt"

        // Empty-state wiring: the `when` arm under `Scaffold`'s
        // content lambda that renders the empty state with the
        // production callback.
        val REGEX_EMPTY_STATE_WIRING = Regex(
            """HomeUiState\.Empty\s*->\s*EmptyState\s*\(\s*[^)]*onAddPersonClick\s*=\s*\{\s*showAddPerson\s*=\s*true\s*\}""",
        )

        // The Button in EmptyState must call `onClick = onAddPersonClick`.
        // We accept any number of parameters between `Button(` and
        // `onClick = onAddPersonClick`.
        val REGEX_BUTTON_ON_ADD_PERSON_CLICK = Regex(
            """Button\s*\([^)]*onClick\s*=\s*onAddPersonClick""",
        )

        // The Button body must render the home_add_person string
        // resource (so TalkBack reads "Add person" and the visible
        // text matches the app-bar action's label).
        val REGEX_BUTTON_HOME_ADD_PERSON_TEXT = Regex(
            """Text\s*\(\s*text\s*=\s*stringResource\s*\(\s*R\.string\.home_add_person""",
        )
    }
}
