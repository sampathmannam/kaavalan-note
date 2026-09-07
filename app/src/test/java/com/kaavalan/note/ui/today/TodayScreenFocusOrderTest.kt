package com.kaavalan.note.ui.today

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the focus-first visual hierarchy. The selector tests verify which
 * instruction wins; this source guard verifies that the winning card is
 * rendered before the reflective/contextual cards in TodayScreen.
 */
class TodayScreenFocusOrderTest {

    @Test
    fun focusCardIsRenderedBeforeContextCards() {
        val source = listOf(
            File("src/main/java/com/kaavalan/note/ui/today/TodayScreen.kt"),
            File("app/src/main/java/com/kaavalan/note/ui/today/TodayScreen.kt"),
        ).firstOrNull { it.exists() }?.readText(Charsets.UTF_8)

        assertNotNull("TodayScreen.kt must be readable in the test worktree", source)
        val text = source.orEmpty()
        val focusIndex = text.indexOf("val focus = focusFirst(brief)")
        val contextIndex = text.indexOf("item { TodaysWinCard() }")

        assertTrue("TodayScreen must compute and render the focus instruction", focusIndex >= 0)
        assertTrue("TodayScreen must render its contextual cards", contextIndex >= 0)
        assertTrue(
            "the focused instruction must appear before the daily summary/context cards",
            focusIndex < contextIndex,
        )
    }
}
