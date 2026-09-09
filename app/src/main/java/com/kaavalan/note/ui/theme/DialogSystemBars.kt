package com.kaavalan.note.ui.theme

import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** Material 3 sheets use a separate window, so the app's theme override must reach it too. */
@Composable
fun DialogSystemBars() {
    val view = LocalView.current
    val window = remember(view) {
        generateSequence(view as View?) { it.parent as? View }
            .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
    }
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    if (window != null) SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}
