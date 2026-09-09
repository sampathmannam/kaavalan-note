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
    primary = Color(0xFF234F70), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEBF4), onPrimaryContainer = Color(0xFF16364D),
    secondary = Color(0xFF4C6272), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E9EF), onSecondaryContainer = Color(0xFF273F50),
    tertiary = Color(0xFF496557), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDEECE2), onTertiaryContainer = Color(0xFF294535),
    background = Color(0xFFF3F5F7), onBackground = Color(0xFF192630),
    surface = Color.White, onSurface = Color(0xFF192630),
    surfaceVariant = Color(0xFFE7EDF1), onSurfaceVariant = Color(0xFF4F606D),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFECF1F4),
    surfaceContainer = Color(0xFFE7EDF1), surfaceContainerHigh = Color(0xFFE0E7ED),
    surfaceContainerHighest = Color(0xFFD9E2E9),
    outline = Color(0xFF748694), outlineVariant = Color(0xFFD3DEE6),
    inverseSurface = Color(0xFF273A48), inverseOnSurface = Color(0xFFF1F5F7),
    inversePrimary = Color(0xFFA5CDED), surfaceTint = Color(0xFF234F70),
)
internal val KaavalanNoteDarkScheme = darkColorScheme(
    primary = Color(0xFFA5CDED), onPrimary = Color(0xFF12364F),
    primaryContainer = Color(0xFF243F52), onPrimaryContainer = Color(0xFFDEEEF9),
    secondary = Color(0xFFB5C9D8), onSecondary = Color(0xFF243A49),
    secondaryContainer = Color(0xFF2D4352), onSecondaryContainer = Color(0xFFD9E7F1),
    tertiary = Color(0xFFAFD0B9), onTertiary = Color(0xFF223C2E),
    tertiaryContainer = Color(0xFF334E3E), onTertiaryContainer = Color(0xFFDCEEDF),
    background = Color(0xFF101A22), onBackground = Color(0xFFE2EBF1),
    surface = Color(0xFF192731), onSurface = Color(0xFFE2EBF1),
    surfaceVariant = Color(0xFF30414E), onSurfaceVariant = Color(0xFFB7C6D1),
    surfaceContainerLowest = Color(0xFF0C151C), surfaceContainerLow = Color(0xFF172630),
    surfaceContainer = Color(0xFF20313D), surfaceContainerHigh = Color(0xFF283A47),
    surfaceContainerHighest = Color(0xFF304350),
    outline = Color(0xFF8A9FAC), outlineVariant = Color(0xFF3D515F),
    inverseSurface = Color(0xFFDFE9F0), inverseOnSurface = Color(0xFF223441),
    inversePrimary = Color(0xFF234F70), surfaceTint = Color(0xFFA5CDED),
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
