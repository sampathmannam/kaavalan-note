package com.kaavalan.note

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-to-Room acceptance test for creating a note with a reminder. */
@RunWith(AndroidJUnit4::class)
class ReminderCaptureFlowTest {

    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun captureTomorrowReminder_persistsAndAppearsInOutbox() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription("Add person")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        runCatching { composeRule.onNodeWithText("Skip").performClick() }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Quick note").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Quick note").performClick()
        val note = "Reminder acceptance note 0915"
        composeRule.onNodeWithText("Note").performTextInput(note)
        composeRule.onNodeWithText("Tomorrow at 9:00")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Save").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(note).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(note).assertIsDisplayed()
        composeRule.onNodeWithText("Tomorrow")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
