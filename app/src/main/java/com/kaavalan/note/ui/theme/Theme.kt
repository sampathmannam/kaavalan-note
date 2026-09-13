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

/** Ink / slate surfaces in both themes; lateness is described without alarm colors. */
internal val KaavalanNoteLightScheme = lightColorScheme(
    primary = Color(0xFF174D6C), onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EBF6), onPrimaryContainer = Color(0xFF113A53),
    secondary = Color(0xFF4B6170), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDFE9EF), onSecondaryContainer = Color(0xFF263F4F),
    tertiary = Color(0xFF176B63), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD0EEE9), onTertiaryContainer = Color(0xFF134A45),
    background = Color(0xFFF6F8FA), onBackground = Color(0xFF15242E),
    surface = Color(0xFFFBFCFD), onSurface = Color(0xFF15242E),
    surfaceVariant = Color(0xFFE6EDF1), onSurfaceVariant = Color(0xFF4B5E6A),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F4F6),
    surfaceContainer = Color(0xFFE9EFF3), surfaceContainerHigh = Color(0xFFE1E9EE),
    surfaceContainerHighest = Color(0xFFD8E2E8),
    outline = Color(0xFF687D8B), outlineVariant = Color(0xFFCAD7DE),
    inverseSurface = Color(0xFF233642), inverseOnSurface = Color(0xFFF2F6F8),
    inversePrimary = Color(0xFFA8D2EC), surfaceTint = Color(0xFF174D6C),
)
internal val KaavalanNoteDarkScheme = darkColorScheme(
    primary = Color(0xFFA8D2EC), onPrimary = Color(0xFF0C354E),
    primaryContainer = Color(0xFF203F53), onPrimaryContainer = Color(0xFFDDEFFA),
    secondary = Color(0xFFB7CBD8), onSecondary = Color(0xFF223A49),
    secondaryContainer = Color(0xFF2A4251), onSecondaryContainer = Color(0xFFDAE8F1),
    tertiary = Color(0xFF91D3CA), onTertiary = Color(0xFF123D39),
    tertiaryContainer = Color(0xFF204B47), onTertiaryContainer = Color(0xFFD0F0EB),
    background = Color(0xFF0C161D), onBackground = Color(0xFFE4EDF2),
    surface = Color(0xFF14232C), onSurface = Color(0xFFE4EDF2),
    surfaceVariant = Color(0xFF2B3F4B), onSurfaceVariant = Color(0xFFB7C7D0),
    surfaceContainerLowest = Color(0xFF091218), surfaceContainerLow = Color(0xFF13232C),
    surfaceContainer = Color(0xFF1A2D37), surfaceContainerHigh = Color(0xFF233844),
    surfaceContainerHighest = Color(0xFF2C4350),
    outline = Color(0xFF8CA0AB), outlineVariant = Color(0xFF3A515E),
    inverseSurface = Color(0xFFE0EAF0), inverseOnSurface = Color(0xFF1D303B),
    inversePrimary = Color(0xFF174D6C), surfaceTint = Color(0xFFA8D2EC),
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
