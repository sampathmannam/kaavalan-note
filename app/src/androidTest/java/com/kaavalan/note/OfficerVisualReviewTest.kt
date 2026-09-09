package com.kaavalan.note

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Reproducible, synthetic-data screenshots for human review; never uses real officer data. */
class OfficerVisualReviewTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun officerWorkspace_lightDarkAndCapture_visualReview() {
        compose.openWorkspace()
        screenshot("01-today-empty")
        compose.saveNote("Review station diary before the evening briefing")
        compose.saveNote("Confirm traffic deployment with the patrol team", "Assigned by me")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Confirm traffic deployment with the patrol team").fetchSemanticsNodes().isNotEmpty() }
        screenshot("02-today-light")
        compose.onNodeWithTag("nav_home").performClick()
        screenshot("03-instructions-light")
        compose.addContact("Inspector Ramesh")
        screenshot("04-contacts-light")
        compose.onNodeWithContentDescription("Settings").performClick()
        screenshot("05-settings-light")
        compose.onNodeWithText("Display theme").performScrollTo().performClick()
        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithContentDescription("Close settings").performClick()
        screenshot("06-contacts-dark")
        compose.onNodeWithTag("nav_today").performClick()
        screenshot("07-today-dark")
        compose.onNodeWithTag("capture_open").performClick()
        compose.onNodeWithText("Note").performTextInput("Follow up on the night patrol briefing")
        awaitKeyboard()
        compose.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled()
        screenshot("08-capture-keyboard-dark")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Compose idle does not include native dialog/IME window animations.
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        android.os.SystemClock.sleep(500)
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: File(instrumentation.targetContext.getExternalFilesDir(null), "ui-review")
        check(directory.exists() || directory.mkdirs())
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "officer-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun largeTextAndLandscape_keepContactsAndCaptureReachable() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command),
        ).bufferedReader().use { it.readText().trim() }
        val oldScale = shell("settings get system font_scale")
        try {
            compose.openWorkspace()
            compose.addContact("Inspector Large Text")
            shell("settings put system font_scale 1.5")
            compose.waitUntil(15_000) { compose.activity.resources.configuration.fontScale >= 1.5f }
            compose.onNodeWithTag("contacts_list").performScrollToNode(hasText("Inspector Large Text"))
            compose.onNodeWithText("Inspector Large Text").assertIsDisplayed()
            screenshot("09-large-text-contacts")
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(15_000) {
                compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            compose.onNodeWithTag("contacts_list").performScrollToNode(hasText("Inspector Large Text"))
            compose.onNodeWithText("Inspector Large Text").assertIsDisplayed()
            screenshot("10-landscape-large-text-contacts")
            compose.onNodeWithTag("capture_open").assertIsDisplayed().performClick()
            compose.onNodeWithText("Note").performTextInput("Accessible duty note")
            awaitKeyboard()
            compose.onNodeWithTag("capture_editor").assertIsDisplayed().assertHeightIsAtLeast(80.dp)
            compose.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled()
            screenshot("11-landscape-large-text-capture")
            compose.onNodeWithText("Save").performClick()
            compose.awaitCaptureSaved()
        } finally {
            shell(if (oldScale == "null" || oldScale.isBlank()) "settings delete system font_scale"
                else "settings put system font_scale $oldScale")
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    private fun awaitKeyboard() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitUntil(15_000) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("dumpsys input_method"),
            ).bufferedReader().use { it.readText().contains("mIsInputViewShown=true") }
        }
        compose.waitForIdle()
    }
}
