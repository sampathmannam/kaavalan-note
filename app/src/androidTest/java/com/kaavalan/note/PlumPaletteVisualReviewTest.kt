package com.kaavalan.note

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.kaavalan.note.debug.WorkspaceTestEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/** Real native theme/status screenshots, using only synthetic data in the isolated QA app. */
class PlumPaletteVisualReviewTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun statusColours_lightAndDark_remainLabelledAndReadable() {
        compose.openWorkspace()
        compose.saveNote("Review the station diary")
        val dao = EntryPointAccessors.fromApplication(
            compose.activity.applicationContext, WorkspaceTestEntryPoint::class.java,
        ).instructions()
        val states = listOf(
            "IN_PROGRESS" to "Prepare the evening briefing",
            "WAITING_ON_OTHER" to "Await the patrol deployment update",
            "REPORTED_DONE" to "Verify the completed equipment check",
            "DONE" to "Station inspection verified",
            "DROPPED" to "Close the duplicate briefing note",
        )
        runBlocking {
            val seed = dao.snapshot().single { it.rawText == "Review the station diary" }
            val now = Instant.now()
            states.forEachIndexed { index, (status, text) ->
                dao.save(seed.copy(
                    id = "plum-review-$status", status = status, title = text, rawText = text,
                    capturedAt = now.minusSeconds((index + 1).toLong()).toString(),
                    completedAt = if (status == "DONE") now.toString() else null,
                ))
            }
        }
        compose.onNodeWithTag("nav_home").performClick()
        listOf("Light", "Dark").forEach { theme ->
            compose.chooseDisplayTheme(theme)
            compose.onNodeWithTag("instructions_list").performScrollToIndex(0)
            compose.onNodeWithText("Review the station diary").assertIsDisplayed()
            screenshot("${theme.lowercase()}-active")
            compose.onNodeWithTag("instructions_list").performScrollToNode(hasText("Ready to verify"))
            compose.onNodeWithText("Ready to verify").assertIsDisplayed()
            screenshot("${theme.lowercase()}-verification")
            compose.onNodeWithTag("instructions_list").performScrollToNode(hasText("Closed without action"))
            compose.onNodeWithText("Closed without action").assertIsDisplayed()
            compose.onNodeWithText("Done").assertIsDisplayed()
            screenshot("${theme.lowercase()}-closed")
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.activity.hasWindowFocus() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: File(instrumentation.targetContext.getExternalFilesDir(null), "ui-review")
        check(directory.exists() || directory.mkdirs())
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "plum-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
