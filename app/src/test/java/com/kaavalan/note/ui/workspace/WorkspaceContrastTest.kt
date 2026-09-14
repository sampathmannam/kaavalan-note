package com.kaavalan.note.ui.workspace

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.kaavalan.note.data.instructions.Status
import com.kaavalan.note.ui.theme.KaavalanNoteDarkScheme
import com.kaavalan.note.ui.theme.KaavalanNoteLightScheme
import com.kaavalan.note.ui.theme.instructionColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceContrastTest {
    @Test fun `text roles meet normal text contrast in both themes`() {
        listOf(KaavalanNoteLightScheme, KaavalanNoteDarkScheme).forEach { scheme ->
            listOf(
                "body" to (scheme.onSurface to scheme.surface),
                "muted body" to (scheme.onSurfaceVariant to scheme.background),
                "button" to (scheme.onPrimary to scheme.primary),
                "secondary button" to (scheme.onSecondary to scheme.secondary),
                "tertiary button" to (scheme.onTertiary to scheme.tertiary),
                "next action" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                "card metadata" to (scheme.onSurfaceVariant to scheme.primaryContainer),
                "in progress" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                "ready to verify" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                "closed" to (scheme.onSurfaceVariant to scheme.surfaceContainerHigh),
                "snackbar" to (scheme.inverseOnSurface to scheme.inverseSurface),
                "snackbar action" to (scheme.inversePrimary to scheme.inverseSurface),
            ).forEach { (label, pair) ->
                val ratio = contrast(pair.first, pair.second)
                assertTrue("$label contrast $ratio is below 4.5:1", ratio >= 4.5)
            }
        }
    }

    @Test fun `every status badge and card stays readable in both themes`() {
        listOf(KaavalanNoteLightScheme, KaavalanNoteDarkScheme).forEach { scheme ->
            Status.entries.forEach { status ->
                val colors = instructionColors(status, scheme)
                assertTrue("$status badge contrast", contrast(colors.content, colors.container) >= 4.5)
                listOf(false, true).forEach { featured ->
                    val card = colors.cardSurface(scheme, featured)
                    assertEquals("Cards must not depend on the parent background", 1f, card.alpha)
                    assertTrue("$status card body contrast", contrast(scheme.onSurface, card) >= 4.5)
                    assertTrue("$status card metadata contrast", contrast(scheme.onSurfaceVariant, card) >= 4.5)
                }
            }
        }
    }

    @Test fun `waiting verification and closure use different status colours`() {
        listOf(KaavalanNoteLightScheme, KaavalanNoteDarkScheme).forEach { scheme ->
            val waiting = instructionColors(Status.WAITING_ON_OTHER, scheme)
            val ready = instructionColors(Status.REPORTED_DONE, scheme)
            val closed = instructionColors(Status.DONE, scheme)
            assertNotEquals(waiting.container, ready.container)
            assertNotEquals(waiting.container, closed.container)
            assertNotEquals(ready.container, closed.container)
            assertEquals(waiting, instructionColors(Status.ACK_PENDING, scheme))
            assertEquals(closed, instructionColors(Status.DROPPED, scheme))
            assertNotEquals(
                instructionColors(Status.OPEN, scheme).container,
                instructionColors(Status.IN_PROGRESS, scheme).container,
            )
        }
    }

    @Test fun `brand actions are muted plum with neutral notebook surfaces`() {
        assertEquals(Color(0xFF633F5A), KaavalanNoteLightScheme.primary)
        assertEquals(Color(0xFFF8F7F8), KaavalanNoteLightScheme.background)
        assertEquals(Color.White, KaavalanNoteLightScheme.surface)
        assertEquals(Color(0xFF27232A), KaavalanNoteLightScheme.onSurface)
        listOf(KaavalanNoteLightScheme.primary, KaavalanNoteDarkScheme.primary).forEach { plum ->
            assertTrue("Plum must not drift back to blue", plum.red > plum.blue && plum.blue > plum.green)
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val high = maxOf(first.luminance(), second.luminance())
        val low = minOf(first.luminance(), second.luminance())
        return (high + 0.05) / (low + 0.05)
    }
}
