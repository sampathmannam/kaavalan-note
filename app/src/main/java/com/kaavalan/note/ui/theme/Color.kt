package com.kaavalan.note.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Kaavalan note design tokens. Per spec §3: no red "overdue" colour anywhere.
 * "Quiet" / "stale" surfaces use a soft amber, not red.
 *
 * v1.6.8: added Light/Dark pairs for the colors that were wrong
 * on the dark surface (`KindBlue`, `KindWarm`, `KindNeutral`,
 * `StaleIndicator`). The pairs are exposed via the
 * `KaavalanKind*()` and `KaavalanStale()` composable accessors below
 * which read from `MaterialTheme.colorScheme` to pick the
 * right one for the current theme.
 */
object KaavalanColors {
    // Primary — calm, not aggressive
    val Primary = Color(0xFF4A6FA5)
    val OnPrimary = Color(0xFFFFFFFF)

    // Surfaces
    val Background = Color(0xFFFAF8F4)   // warm off-white
    val Surface = Color(0xFFFFFFFF)
    val SurfaceVariant = Color(0xFFEFEAE0)
    val OnSurface = Color(0xFF1F1B16)
    val OnSurfaceMuted = Color(0xFF6B6358)

    // Quiet / stale indicator — amber, NOT red. The legacy single
    // value is kept for callers that read it from a non-@Composable
    // context (e.g. the dot preview in the dev fixture).
    val Quiet = Color(0xFFD4A24C)

    // Semantic
    val Done = Color(0xFF5A8A5A)        // muted green, not bright
    val PriorityHigh = Color(0xFF8B5A2B) // warm brown, not red
    val PriorityNormal = Color(0xFF6B6358)
    val PriorityLow = Color(0xFFB8B0A4)

    // Outlines
    val Outline = Color(0xFFD8D2C5)
    val OutlineMuted = Color(0xFFEAE5D9)

    // v1.6.8: tag-kind color pairs (light/dark). Light values
    // are the v1.6.7 values (chosen to sit calmly on a warm
    // off-white surface). Dark values are lighter tones that
    // hold up against the 0xFF2F2A23 surfaceVariant in dark
    // mode — the old light values were too dark to read as
    // chip dots on the dark surface.
    val KindBlueLight = Color(0xFF6B7AA1)
    val KindBlueDark = Color(0xFFA9B4D2)
    val KindWarmLight = Color(0xFFB58A4D)
    val KindWarmDark = Color(0xFFD9B27A)
    val KindNeutralLight = Color(0xFF6F6F6F)
    val KindNeutralDark = Color(0xFFB0AEA8)

    // v1.6.8: the person-list stale dot. Light value is the
    // existing 0xFFD9A05B; dark value is brighter so the dot
    // is visible on the 0xFF2F2A23 surface.
    val StaleIndicatorLight = Color(0xFFD9A05B)
    val StaleIndicatorDark = Color(0xFFE6B783)
}

/**
 * v1.6.8: theme-aware accessors. Use these from a Composable
 * instead of reading the `KaavalanColors.KindXxxLight` constants
 * directly so the colour flips when the user switches themes.
 */
object KaavalanThemeTokens {
    @Composable
    @ReadOnlyComposable
    fun kindBlue(): Color = if (isDarkSurface(MaterialTheme.colorScheme.surface)) {
        KaavalanColors.KindBlueDark
    } else {
        KaavalanColors.KindBlueLight
    }

    @Composable
    @ReadOnlyComposable
    fun kindWarm(): Color = if (isDarkSurface(MaterialTheme.colorScheme.surface)) {
        KaavalanColors.KindWarmDark
    } else {
        KaavalanColors.KindWarmLight
    }

    @Composable
    @ReadOnlyComposable
    fun kindNeutral(): Color = if (isDarkSurface(MaterialTheme.colorScheme.surface)) {
        KaavalanColors.KindNeutralDark
    } else {
        KaavalanColors.KindNeutralLight
    }

    @Composable
    @ReadOnlyComposable
    fun staleIndicator(): Color = if (isDarkSurface(MaterialTheme.colorScheme.surface)) {
        KaavalanColors.StaleIndicatorDark
    } else {
        KaavalanColors.StaleIndicatorLight
    }
}

/**
 * Is [surface] a dark surface? `luminance() < 0.5` is the
 * standard heuristic.
 *
 * The tokens above resolve against the scheme that is actually
 * applied, and nothing else. They used to read
 * `isSystemInDarkTheme() || MaterialThemeIsDark()`, which
 * disagrees with the applied scheme in one reachable case: the
 * user picks `ThemeMode.Light` while the OS is in dark mode.
 * `MainActivity` maps that to `darkTheme = false` and
 * `KaavalanNoteTheme` applies the light scheme, but the first
 * term of the disjunction was still true — so the tag dots and
 * the person-list stale dot were painted in their dark-mode
 * tones, which are deliberately *lighter* for contrast against a
 * dark surface, on white. The stale dot is the whole quiet-nudge
 * signal on the home list, and at `0xFFE6B783` on `0xFFFFFFFF`
 * it is close to invisible.
 *
 * Reading the applied surface is also sufficient on its own. The
 * `isSystemInDarkTheme()` term was a fallback for callers outside
 * a `MaterialTheme { }` block, where `MaterialTheme.colorScheme`
 * returns the M3 default rather than throwing — but every call
 * site is inside the app's theme, and the fallback cost more than
 * it covered.
 *
 * `internal` and non-composable so the light/dark decision can be
 * asserted directly: this project's unit tests cannot render
 * Compose (see [com.kaavalan.note.ui.home.HomeScreenTest] on
 * Robolectric 4.13's `createComposeRule()` limitation).
 */
internal fun isDarkSurface(surface: Color): Boolean = surface.luminance() < 0.5f

private fun Color.luminance(): Float {
    // sRGB → relative luminance, simplified. Good enough for a
    // light/dark heuristic on the surface colour; doesn't need
    // to be colour-science-precise.
    val r = red
    val g = green
    val b = blue
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}
