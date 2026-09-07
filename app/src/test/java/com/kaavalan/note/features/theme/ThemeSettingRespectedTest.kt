package com.kaavalan.note.features.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every screen has to obey the user's Theme setting.
 *
 * `KaavalanNoteTheme`'s `darkTheme` parameter defaults to
 * `isSystemInDarkTheme()`. That default is a reasonable one for a
 * `@Preview`, and a trap for an Activity: omit the argument and the
 * screen silently follows the OS while the rest of the app follows
 * the setting. `QuickNoteActivity` did exactly that from v1.9.10 —
 * choosing Theme -> Light on a dark-mode phone gave a dark Quick
 * Note capture screen in front of a light app, and vice versa. The
 * widget is the app's fastest capture path, so it is the screen a
 * user sees most often out of context.
 *
 * The resolution now lives in one place, [appDarkTheme], because
 * the previous arrangement — the `ThemeMode` `when` inlined at each
 * call site — is what made forgetting possible in the first place.
 *
 * A source scan rather than a render test: this project's unit
 * tests cannot render Compose (Robolectric 4.13's
 * `createComposeRule()` limitation, documented in
 * `HomeScreenTest`), and the property worth pinning is structural
 * anyway — "no call site takes the default" is a statement about
 * the call sites, not about a rendered pixel.
 */
class ThemeSettingRespectedTest {

    private val mainSrc = File("src/main/java/com/kaavalan/note")

    private fun sources(): List<File> =
        mainSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `every KaavalanNoteTheme call site passes darkTheme explicitly`() {
        assertTrue("main source tree should be readable from the module dir", mainSrc.isDirectory)

        // The theme's own declaration is the one place the bare name
        // legitimately appears without an argument list.
        val declaration = "app/src/main/java/com/kaavalan/note/ui/theme/Theme.kt"
        val offenders = sources()
            .filterNot { it.path.endsWith("ui/theme/Theme.kt") }
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (i, line) ->
                    // `KaavalanNoteTheme {` — the trailing-lambda form
                    // with no argument list, i.e. taking the default.
                    if (Regex("""KaavalanNoteTheme\s*\{""").containsMatchIn(line)) {
                        "${file.relativeTo(mainSrc)}:${i + 1}: $line"
                    } else {
                        null
                    }
                }
            }

        assertTrue(
            "These call sites invoke KaavalanNoteTheme without a darkTheme argument, so " +
                "they fall back to isSystemInDarkTheme() and ignore the user's Theme " +
                "setting. Pass `darkTheme = appDarkTheme()` instead. (The declaration in " +
                "$declaration is exempt.)\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * The counterpart: the ThemeMode -> Boolean mapping must exist
     * in exactly one place. Two copies is how the call sites drifted
     * apart to begin with.
     */
    @Test
    fun `the ThemeMode to dark-boolean mapping lives in exactly one place`() {
        val mapping = Regex("""ThemeMode\.Dark\s*->\s*true""")
        val sites = sources().filter { mapping.containsMatchIn(it.readText()) }

        assertEquals(
            "The ThemeMode -> dark-boolean mapping should exist only in appDarkTheme(). " +
                "Found it in: " + sites.joinToString { it.relativeTo(mainSrc).path },
            1,
            sites.size,
        )
        assertTrue(
            "The single mapping should be the one in ThemeViewModel.kt (appDarkTheme). " +
                "Found: ${sites.firstOrNull()?.relativeTo(mainSrc)}",
            sites.single().path.endsWith("features/theme/ThemeViewModel.kt"),
        )
    }
}
