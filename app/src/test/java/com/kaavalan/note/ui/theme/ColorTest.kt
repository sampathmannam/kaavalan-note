package com.kaavalan.note.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ColorTest {

    @Test
    fun `no red color in palette or semantic colors`() {
        // Spec rule: "no red 'overdue' badge". The colour palette must
        // not contain any saturated red. Amber for "quiet" is allowed.
        val palette: List<Color> = listOf(
            KaavalanColors.Quiet,
            KaavalanColors.Primary,
            KaavalanColors.Surface,
            KaavalanColors.OnSurface,
            KaavalanColors.OnSurfaceMuted,
        )
        palette.forEach { color ->
            val r = color.red
            val g = color.green
            val b = color.blue
            // "Red" = red channel clearly dominant and not just a hint.
            val isRed = r > 0.6f && r > g * 1.5f && r > b * 1.5f
            assertFalse(
                "Colour $color is red-dominant; spec forbids red badges",
                isRed
            )
        }
    }

    @Test
    fun `quiet colour is amber, not red`() {
        // The "stale" / "quiet" indicator must be amber, not red.
        val quiet = KaavalanColors.Quiet
        val r = quiet.red
        val g = quiet.green
        assertTrue("Amber needs significant green", g > 0.4f)
        assertTrue("Amber has red component", r > 0.6f)
    }

    /**
     * The theme-token accessors pick their light/dark variant from
     * the applied colour scheme's surface. Assert the heuristic
     * actually separates the two schemes — if it did not, every
     * token would resolve to one variant regardless of theme.
     *
     * Asserted against the real `KaavalanNoteLightScheme` /
     * `KaavalanNoteDarkScheme` rather than copies of their values,
     * so a future palette change that pushes a surface across the
     * 0.5 boundary fails here.
     */
    @Test
    fun `isDarkSurface separates the light and dark schemes`() {
        assertFalse(
            "the light scheme's surface must not read as dark",
            isDarkSurface(KaavalanNoteLightScheme.surface),
        )
        assertTrue(
            "the dark scheme's surface must read as dark",
            isDarkSurface(KaavalanNoteDarkScheme.surface),
        )
    }

    /**
     * The regression guard for "app set to Light while the OS is in
     * dark mode".
     *
     * The accessors used to resolve on
     * `isSystemInDarkTheme() || MaterialThemeIsDark()`. With
     * `ThemeMode.Light` chosen on a phone in dark mode,
     * `MainActivity` passes `darkTheme = false` and the light scheme
     * is applied — but the first term was still true, so the tag
     * dots and the person-list stale dot rendered in their dark-mode
     * tones (which are lighter, for contrast against a dark surface)
     * on white. `StaleIndicatorDark` at 0xFFE6B783 on a 0xFFFFFFFF
     * surface is barely visible, and that dot is the home list's
     * entire quiet-nudge signal.
     *
     * A source scan because this project's unit tests cannot render
     * Compose — the same pattern
     * [com.kaavalan.note.ui.home.PersonLinksRowTest] and
     * `NoteBarTest` use, for the same reason.
     */
    @Test
    fun `theme tokens do not consult the system dark setting`() {
        val source = File("src/main/java/com/kaavalan/note/ui/theme/Color.kt")
        assertTrue("Color.kt should be readable from the module dir", source.exists())
        assertFalse(
            "Color.kt must not read isSystemInDarkTheme(): the tokens have to follow " +
                "the applied colour scheme, which already accounts for the user's " +
                "ThemeMode override. Consulting the OS setting instead re-breaks " +
                "'app Light on a dark-mode phone'.",
            source.readText().contains("isSystemInDarkTheme"),
        )
    }

    /**
     * The pairs exist so each variant is legible on its own
     * background. Pin the direction — the dark variant is the
     * lighter colour — because the bug above was precisely a dark
     * variant landing on a light surface.
     */
    @Test
    fun `dark variants are lighter than their light counterparts`() {
        listOf(
            "kindBlue" to (KaavalanColors.KindBlueLight to KaavalanColors.KindBlueDark),
            "kindWarm" to (KaavalanColors.KindWarmLight to KaavalanColors.KindWarmDark),
            "kindNeutral" to (KaavalanColors.KindNeutralLight to KaavalanColors.KindNeutralDark),
            "staleIndicator" to
                (KaavalanColors.StaleIndicatorLight to KaavalanColors.StaleIndicatorDark),
        ).forEach { (name, pair) ->
            val (light, dark) = pair
            val lightLuma = 0.2126f * light.red + 0.7152f * light.green + 0.0722f * light.blue
            val darkLuma = 0.2126f * dark.red + 0.7152f * dark.green + 0.0722f * dark.blue
            assertTrue(
                "$name: the dark-theme variant must be the lighter colour (it sits on a " +
                    "dark surface). Got light=$lightLuma dark=$darkLuma",
                darkLuma > lightLuma,
            )
        }
    }
}
