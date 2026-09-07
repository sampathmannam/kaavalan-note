package com.kaavalan.note.features.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kaavalan.note.data.preferences.KaavalanPreferences
import com.kaavalan.note.data.preferences.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Tier 1.4 (v2.0): theme switcher ViewModel.
 *
 * The `themeMode` flow is the single source of truth. The
 * [KaavalanPreferences] DataStore is the persistent backing
 * store. The actual theme is applied at the root composable
 * in [com.kaavalan.note.MainActivity.setContent] (we read the
 * same flow and pick `KaavalanLightScheme` / `KaavalanDarkScheme`
 * accordingly).
 */
@dagger.hilt.android.lifecycle.HiltViewModel
class ThemeViewModel @Inject constructor(
    private val preferences: KaavalanPreferences,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = preferences.themeMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ThemeMode.System,
        )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }
}

/**
 * Turns the stored [ThemeMode] into the boolean
 * [com.kaavalan.note.ui.theme.KaavalanNoteTheme] wants.
 *
 * Every `setContent` that themes the app must pass
 * `darkTheme = appDarkTheme()`. Leaving the argument off gets the
 * parameter's default, `isSystemInDarkTheme()`, which silently
 * ignores the user's Theme setting — which is what
 * `QuickNoteActivity` did from v1.9.10 until this was extracted:
 * the widget's Quick Note screen followed the OS while the rest of
 * the app followed the setting, so choosing Light on a dark-mode
 * phone produced a dark capture screen in front of a light app.
 *
 * It lives here, in one place, because the previous arrangement
 * (the `when` inlined at each call site) is what made forgetting
 * possible. `ThemeSettingRespectedTest` pins that no call site
 * reverts to the bare form.
 */
@Composable
fun appDarkTheme(): Boolean {
    val viewModel: ThemeViewModel = hiltViewModel()
    val mode by viewModel.themeMode.collectAsStateWithLifecycle()
    return when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}
