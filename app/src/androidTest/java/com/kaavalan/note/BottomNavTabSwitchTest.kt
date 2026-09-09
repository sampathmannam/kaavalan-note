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
class BottomNavTabSwitchTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun today_instructions_contacts_andSettings_areReachable() {
        composeRule.openWorkspace()
        composeRule.onNodeWithTag("workspace_title").assertTextEquals("Today")
        composeRule.onNodeWithTag("nav_home").performClick()
        composeRule.onNodeWithTag("workspace_title").assertTextEquals("Instructions")
        composeRule.onNodeWithTag("nav_contacts").performClick()
        composeRule.onNodeWithTag("workspace_title").assertTextEquals("Contacts")
        composeRule.onNodeWithText("Your officers, staff and other work contacts.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithTag("settings_sheet_title").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close settings").performClick()
        composeRule.onNodeWithTag("workspace_title").assertTextEquals("Contacts")
        composeRule.onNodeWithTag("nav_today").performClick()
        composeRule.onNodeWithTag("workspace_title").assertTextEquals("Today")
    }
}
