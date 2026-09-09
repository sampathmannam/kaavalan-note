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
class HomeEmptyStateTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun captureIsAvailableFromEachTab_andDraftSurvivesNavigation() {
        composeRule.openWorkspace()
        composeRule.onNodeWithTag("nav_contacts").performClick()
        composeRule.onNodeWithTag("capture_open").performClick()
        composeRule.onNodeWithText("Note").performTextInput("Draft to continue")
        composeRule.onNodeWithContentDescription("Close").performClick()
        composeRule.onNodeWithTag("nav_home").performClick()
        composeRule.onNodeWithTag("capture_open").performClick()
        composeRule.onNodeWithText("Draft to continue").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsEnabled()
    }
}
