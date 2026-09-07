package com.kaavalan.note

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for share/widget/tile capture. MainActivity owns the
 * activity-scoped RootViewModel; HomeScreen must receive that same instance
 * instead of asking Hilt for a navigation-scoped replacement.
 */
class MainScaffoldIngressWiringTest {

    @Test
    fun mainScaffold_passesItsRootViewModelToHome() {
        val candidates = listOf(
            File("src/main/java/com/kaavalan/note/MainActivity.kt"),
            File("app/src/main/java/com/kaavalan/note/MainActivity.kt"),
        )
        val source = candidates.firstOrNull { it.exists() }?.readText()
        assertTrue("MainActivity.kt must be readable in the test worktree", source != null)
        assertTrue(
            "HomeScreen must receive MainScaffold's activity-scoped RootViewModel so ingress events reach CaptureSheet.",
            Regex("""HomeScreen\s*\([\s\S]*?rootViewModel\s*=\s*rootViewModel""")
                .containsMatchIn(source.orEmpty()),
        )
    }

    @Test
    fun settingsSheetUsesTheSelectedNavigationState() {
        val source = listOf(
            File("src/main/java/com/kaavalan/note/MainActivity.kt"),
            File("app/src/main/java/com/kaavalan/note/MainActivity.kt"),
        ).firstOrNull { it.exists() }?.readText().orEmpty()

        assertTrue(
            "opening Settings must select its navigation item while the configuration sheet is visible",
            source.contains(
                "val selectedNavRoute = if (showSettings) Routes.SETTINGS else currentRoute",
            ),
        )
        assertTrue(
            "BottomNav must receive the temporary selected route rather than the underlying screen route",
            source.contains("currentRoute = selectedNavRoute"),
        )
    }
}
