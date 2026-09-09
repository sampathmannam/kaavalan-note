package com.kaavalan.note.ui.workspace

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.kaavalan.note.ui.theme.KaavalanNoteDarkScheme
import com.kaavalan.note.ui.theme.KaavalanNoteLightScheme
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceContrastTest {
    @Test fun `text roles meet normal text contrast in both themes`() {
        listOf(KaavalanNoteLightScheme, KaavalanNoteDarkScheme).forEach { scheme ->
            listOf(
                "body" to (scheme.onSurface to scheme.surface),
                "muted body" to (scheme.onSurfaceVariant to scheme.background),
                "button" to (scheme.onPrimary to scheme.primary),
                "next action" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                "card metadata" to (scheme.onSurfaceVariant to scheme.primaryContainer),
                "navigation" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
            ).forEach { (label, pair) ->
                val ratio = contrast(pair.first, pair.second)
                assertTrue("$label contrast $ratio is below 4.5:1", ratio >= 4.5)
            }
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val high = maxOf(first.luminance(), second.luminance())
        val low = minOf(first.luminance(), second.luminance())
        return (high + 0.05) / (low + 0.05)
    }
}
