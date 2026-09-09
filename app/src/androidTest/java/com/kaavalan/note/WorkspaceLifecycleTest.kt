package com.kaavalan.note

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise composition disposal in the test body, not just implicit rule teardown. */
@RunWith(AndroidJUnit4::class)
class WorkspaceLifecycleTest {
    @get:Rule(order = 0)
    val permissions = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun tabSwitchAndSettingsDismiss_surviveRepeatedRecreationAndClose() {
        composeRule.openWorkspace()
        repeat(10) {
            composeRule.onNodeWithTag("nav_home").performClick()
            composeRule.onNodeWithTag("workspace_title").assertTextEquals("Instructions")
            composeRule.onNodeWithTag("nav_contacts").performClick()
            composeRule.onNodeWithTag("workspace_title").assertTextEquals("Contacts")
            composeRule.onNodeWithContentDescription("Settings").performClick()
            composeRule.onNodeWithTag("settings_sheet_title").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Close settings").performClick()
            composeRule.onNodeWithTag("nav_today").performClick()

            // No waitForIdle here: Android may recreate while navigation is animating.
            composeRule.activityRule.scenario.recreate()
            composeRule.openWorkspace()
            composeRule.onNodeWithTag("workspace_title").assertTextEquals("Today")
        }
        composeRule.activityRule.scenario.close()
        assertEquals(Lifecycle.State.DESTROYED, composeRule.activityRule.scenario.state)
    }

    @Test
    fun visibleSettings_surviveRepeatedActivityRecreationAndClose() {
        composeRule.openWorkspace()
        composeRule.onNodeWithTag("nav_contacts").performClick()
        repeat(5) {
            composeRule.onNodeWithContentDescription("Settings").performClick()
            composeRule.onNodeWithTag("settings_sheet_title").assertIsDisplayed()
            // Recreate with a second Compose window still attached to the Activity.
            composeRule.activityRule.scenario.recreate()
            composeRule.openWorkspace()
            composeRule.onNodeWithTag("workspace_title").assertTextEquals("Contacts")
        }
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithTag("settings_sheet_title").assertIsDisplayed()
        composeRule.activityRule.scenario.close()
        assertEquals(Lifecycle.State.DESTROYED, composeRule.activityRule.scenario.state)
    }
}
