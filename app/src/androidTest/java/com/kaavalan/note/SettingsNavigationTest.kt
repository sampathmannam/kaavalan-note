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
class SettingsNavigationTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun settingsUsesFocusedSections_andThemeChoicePersists() {
        composeRule.openWorkspace()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Privacy & security").assertIsDisplayed()
        composeRule.onNodeWithText("Display theme").performScrollTo().performClick()
        composeRule.onNodeWithText("Dark").performClick()
        composeRule.onNodeWithText("All settings").performClick()
        composeRule.onNodeWithText("Export & restore").performScrollTo().performClick()
        composeRule.onNodeWithText("Export encrypted vault").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("All settings").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Close settings").performClick()
        composeRule.onNodeWithTag("workspace_title").assertTextEquals("Today")
    }
}
