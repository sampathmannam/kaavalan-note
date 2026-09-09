package com.kaavalan.note.data.local

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/** A cold launch must neither replace officer data nor block on fixture preferences. */
class AppInitializerReseedGateTimingTest {
    private fun source(): String {
        val relative = "src/main/java/com/kaavalan/note/data/local/AppInitializer.kt"
        return listOf(File(relative), File("app/$relative")).first { it.exists() }.readText()
    }

    @Test fun startup_neverLoadsOrReseedsSampleData() {
        val text = source()
        assertFalse(text.contains("fixtureLoader"))
        assertFalse(text.contains("reseedIfStale("))
        assertFalse(text.contains("loadFromAssets("))
    }

    @Test fun startup_doesNotBlockOnFixtureOrOnboardingPreferences() {
        assertFalse(source().contains("runBlocking"))
    }
}
