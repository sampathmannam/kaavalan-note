package com.kaavalan.note

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Acceptance coverage for the officer workspace using real Activity, Hilt and Room. */
@RunWith(AndroidJUnit4::class)
class ReminderCaptureFlowTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun reminder_isVisibleTomorrowAndSurvivesActivityRecreation() {
        composeRule.openWorkspace()
        composeRule.onNodeWithTag("capture_open").performClick()
        val note = "Review tomorrow duty chart"
        composeRule.onNodeWithText("Note").performTextInput(note)
        composeRule.onNodeWithText("Tomorrow at 9:00").performScrollTo().performClick()
        composeRule.onNodeWithText("Save").performClick()
        composeRule.awaitCaptureSaved()
        composeRule.onNodeWithTag("nav_home").performClick()
        composeRule.onNodeWithText(note).performClick()
        composeRule.onNodeWithText("Reminder").assertExists()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithTag("instruction_detail_text").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("instruction_detail_text").assertTextEquals(note)
        composeRule.onNodeWithContentDescription("Clear reminder").performScrollTo().assertIsDisplayed()
    }
}
