package com.kaavalan.note.features.theme

import androidx.test.core.app.ApplicationProvider
import com.kaavalan.note.data.preferences.KaavalanPreferences
import com.kaavalan.note.data.preferences.ThemeMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Tier 1.4 (v2.0): the theme switcher ViewModel.
 *
 * The VM's [themeMode] is a `stateIn` over
 * [KaavalanPreferences.themeMode] (a DataStore Preferences
 * `Flow<ThemeMode>`). Setting a new mode via
 * `vm.setThemeMode(...)` calls
 * `preferences.setThemeMode(...)` which is a `suspend` call
 * to `dataStore.edit { ... }`. Under Robolectric the main
 * looper is paused, and DataStore's emission path uses the
 * main looper to deliver the result of the write. If we
 * don't idle the looper, the write coroutine blocks forever.
 *
 * Strategy: do NOT replace the main dispatcher (DataStore
 * uses its own internal IO scope, so we don't need to
 * control it). Use `runBlocking` (real time, real
 * dispatchers) and idle the Robolectric main looper after
 * each `setThemeMode` so the DataStore emission can fire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThemeViewModelTest {

    @Before
    fun setUp() {
        // DataStore is a process singleton. Deleting its backing file does not
        // clear the value already cached in memory and can race an in-flight
        // write from the preceding test. Reset through the public API instead.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        runBlocking { KaavalanPreferences(ctx).setThemeMode(ThemeMode.System) }
        repeat(3) { ShadowLooper.idleMainLooper() }
    }

    @After
    fun tearDown() {
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `initial theme mode is System`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = KaavalanPreferences(ctx)
        ShadowLooper.idleMainLooper()
        // The VM's stateIn starts eagerly and reads from
        // DataStore. Idle the looper so the first read fires.
        val vm = ThemeViewModel(prefs)
        ShadowLooper.idleMainLooper()
        assertEquals(ThemeMode.System, vm.themeMode.value)
    }

    @Test
    fun `setThemeMode to Light propagates to the StateFlow`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = KaavalanPreferences(ctx)
        val vm = ThemeViewModel(prefs)
        repeat(3) { ShadowLooper.idleMainLooper() }
        runBlocking { prefs.setThemeMode(ThemeMode.Light) }
        // DataStore's emission goes through the main looper
        // to deliver the new value to the StateFlow. Idle
        // the looper repeatedly so the read sees the
        // post-write state.
        repeat(5) { ShadowLooper.idleMainLooper() }
        val read = runBlocking { prefs.themeMode.first() }
        assertEquals(ThemeMode.Light, read)
        assertEquals(ThemeMode.Light, vm.themeMode.value)
    }

    @Test
    fun `setThemeMode to Dark propagates to the StateFlow`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = KaavalanPreferences(ctx)
        val vm = ThemeViewModel(prefs)
        repeat(3) { ShadowLooper.idleMainLooper() }
        runBlocking { prefs.setThemeMode(ThemeMode.Dark) }
        repeat(5) { ShadowLooper.idleMainLooper() }
        val read = runBlocking { prefs.themeMode.first() }
        assertEquals(ThemeMode.Dark, read)
        assertEquals(ThemeMode.Dark, vm.themeMode.value)
    }

    @Test
    fun `setThemeMode to Light then to Dark - the second value wins`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = KaavalanPreferences(ctx)
        val vm = ThemeViewModel(prefs)
        repeat(3) { ShadowLooper.idleMainLooper() }
        runBlocking { prefs.setThemeMode(ThemeMode.Light) }
        repeat(3) { ShadowLooper.idleMainLooper() }
        runBlocking { prefs.setThemeMode(ThemeMode.Dark) }
        repeat(5) { ShadowLooper.idleMainLooper() }
        val read = runBlocking { prefs.themeMode.first() }
        assertEquals(ThemeMode.Dark, read)
    }
}
