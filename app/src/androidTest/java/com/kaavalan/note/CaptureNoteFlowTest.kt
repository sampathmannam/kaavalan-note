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
class CaptureNoteFlowTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun personalNote_canBeCompletedAndReopened_withoutAddingContact() {
        try {
        composeRule.openWorkspace()
        android.util.Log.d("OfficerAcceptance", "Personal-note workspace opened")
        val text = "Review station diary at duty change"
        composeRule.saveNote(text)
        android.util.Log.d("OfficerAcceptance", "Personal note saved")
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(text).performClick()
        composeRule.onNodeWithTag("detail_done").assertIsDisplayed().performClick()
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithText("Instruction").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("nav_home").performClick()
        composeRule.onNodeWithText("Closed").assertIsDisplayed().performClick()
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(text).performClick()
        composeRule.onNodeWithTag("detail_reopen").assertIsDisplayed().performClick()
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithText("Instruction").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithText("All open").assertIsDisplayed().performClick()
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(text).assertIsDisplayed()
        } catch (failure: Throwable) {
            android.util.Log.e("OfficerAcceptance", "Completion workflow failed before activity teardown", failure)
            throw failure
        }
    }
}
