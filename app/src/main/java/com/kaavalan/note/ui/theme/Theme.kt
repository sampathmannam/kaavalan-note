package com.kaavalan.note.ui.theme

import androidx.compose.ui.unit.dp
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Muted plum actions, neutral notebook surfaces; lateness never uses alarm colors. */
internal val KaavalanNoteLightScheme = lightColorScheme(
    primary = KaavalanColors.Primary, onPrimary = Color.White,
    primaryContainer = Color(0xFFECDDE8), onPrimaryContainer = Color(0xFF4A2942),
    secondary = Color(0xFF685865), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE4EB), onSecondaryContainer = Color(0xFF483A46),
    tertiary = Color(0xFF355E49), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCEADF), onTertiaryContainer = Color(0xFF284D36),
    background = KaavalanColors.Background, onBackground = KaavalanColors.OnSurface,
    surface = KaavalanColors.Surface, onSurface = KaavalanColors.OnSurface,
    surfaceVariant = KaavalanColors.SurfaceVariant, onSurfaceVariant = KaavalanColors.OnSurfaceMuted,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F0F4),
    surfaceContainer = Color(0xFFEFE9EF), surfaceContainerHigh = Color(0xFFE8E1E9),
    surfaceContainerHighest = Color(0xFFE1D8E2),
    outline = KaavalanColors.Outline, outlineVariant = KaavalanColors.OutlineMuted,
    inverseSurface = Color(0xFF342C35), inverseOnSurface = Color(0xFFF6EFF5),
    inversePrimary = Color(0xFFDCB8D1), surfaceTint = KaavalanColors.Primary,
)
internal val KaavalanNoteDarkScheme = darkColorScheme(
    primary = Color(0xFFDCB8D1), onPrimary = Color(0xFF382130),
    primaryContainer = Color(0xFF4B3243), onPrimaryContainer = Color(0xFFF3DEEE),
    secondary = Color(0xFFCFBFCC), onSecondary = Color(0xFF382E36),
    secondaryContainer = Color(0xFF453A43), onSecondaryContainer = Color(0xFFEDDFE9),
    tertiary = Color(0xFFA5CEB0), onTertiary = Color(0xFF153923),
    tertiaryContainer = Color(0xFF2B4935), onTertiaryContainer = Color(0xFFD3ECD9),
    background = Color(0xFF171418), onBackground = Color(0xFFEEE7EF),
    surface = Color(0xFF201B21), onSurface = Color(0xFFEEE7EF),
    surfaceVariant = Color(0xFF3E343D), onSurfaceVariant = Color(0xFFCEC1CD),
    surfaceContainerLowest = Color(0xFF110F12), surfaceContainerLow = Color(0xFF211C22),
    surfaceContainer = Color(0xFF29222B), surfaceContainerHigh = Color(0xFF332B35),
    surfaceContainerHighest = Color(0xFF3E3440),
    outline = Color(0xFF9B8B99), outlineVariant = Color(0xFF51434F),
    inverseSurface = Color(0xFFECE4ED), inverseOnSurface = Color(0xFF302831),
    inversePrimary = KaavalanColors.Primary, surfaceTint = Color(0xFFDCB8D1),
)

/**
 * Tier 1.4 (v2.0): the theme now accepts an explicit
 * [darkTheme] override. The root composable (MainActivity)
 * computes `useDark = themeViewModel.mode == Dark` (or
 * `themeViewModel.mode == System && isSystemInDarkTheme()`)
 * and passes the result here, so the swap is immediate
 * without an app restart.
 */
@Composable
fun KaavalanNoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colourScheme = if (darkTheme) KaavalanNoteDarkScheme else KaavalanNoteLightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // v1.6.7: statusBarColor is deprecated in R+ (and a hard
            // deprecation in Android 15 / API 35). With
            // enableEdgeToEdge() in MainActivity the system bar
            // colour is now driven by the content's surface colour
            // (Material 3's windowInsets handling); the previous
            // explicit setStatusBarColor call is no longer needed
            // and the WindowInsetsController below is the supported
            // way to switch the status-bar icon tint for the
            // current theme.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colourScheme,
        typography = KaavalanNoteTypography,
        shapes = androidx.compose.material3.Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        ),
        content = content,
    )
}
