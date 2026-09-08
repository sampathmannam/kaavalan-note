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
class AddPersonFlowTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun addContact_isSearchableAndOpensItsInstructions() {
        try {
            composeRule.openWorkspace()
            composeRule.addContact("SI Ravi Test")
            android.util.Log.d("OfficerAcceptance", "Contact persisted")
            composeRule.onNodeWithTag("workspace_search").performTextInput("ravi")
            composeRule.onNodeWithText("SI Ravi Test").performClick()
            composeRule.waitUntil(15_000) { composeRule.onAllNodesWithText("Open instructions").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithText("SI Ravi Test").assertIsDisplayed()
            composeRule.onNodeWithText("Open instructions").assertIsDisplayed()
            android.util.Log.d("OfficerAcceptance", "Contact timeline opened")
            composeRule.onNodeWithText("Add instruction for SI Ravi Test").performClick()
            composeRule.onNodeWithText("Note").performTextInput("Review traffic deployment")
            composeRule.onNodeWithText("Assigned by me").performScrollTo().performClick()
            composeRule.onNodeWithText("Save").performClick()
            composeRule.awaitCaptureSaved()
            composeRule.waitUntil(15_000) { composeRule.onAllNodesWithText("Review traffic deployment").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithText("Review traffic deployment").assertIsDisplayed()
            android.util.Log.d("OfficerAcceptance", "Linked instruction persisted and visible")
        } catch (failure: Throwable) {
            android.util.Log.e("OfficerAcceptance", "Contact workflow failed before activity teardown", failure)
            throw failure
        }
    }
}
