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
class OnboardingFlowTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun introductionLeadsToToday_andDoesNotReturnAfterRecreation() {
        composeRule.openWorkspace()
        composeRule.onNodeWithTag("workspace_title").assertTextEquals("Today")
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithTag("workspace_title").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Skip").assertDoesNotExist()
        composeRule.onNodeWithTag("workspace_title").assertTextEquals("Today")
    }
}
