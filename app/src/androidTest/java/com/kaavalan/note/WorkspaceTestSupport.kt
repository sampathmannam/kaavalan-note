package com.kaavalan.note

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

internal fun ComposeContentTestRule.openWorkspace() {
    waitUntil(15_000) {
        onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithTag("workspace_title").fetchSemanticsNodes().isNotEmpty()
    }
    if (onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty()) onNodeWithText("Skip").performClick()
    waitUntil(15_000) { onAllNodesWithTag("workspace_title").fetchSemanticsNodes().isNotEmpty() }
    // Wait for on-screen controls, not only published semantics during startup.
    waitUntil(15_000) { runCatching { onNodeWithTag("capture_open").assertIsDisplayed() }.isSuccess }
    onNodeWithTag("capture_open").assertIsDisplayed()
}

internal fun ComposeContentTestRule.saveNote(text: String, responsibility: String? = null) {
    onNodeWithTag("capture_open").performClick()
    waitUntil(15_000) { onAllNodesWithText("Note").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Note").performTextInput(text)
    responsibility?.let { onNodeWithText(it).performScrollTo().performClick() }
    waitUntil(15_000) { runCatching { onNodeWithText("Save").assertIsEnabled() }.isSuccess }
    onNodeWithText("Save").performClick()
    awaitCaptureSaved()
}

internal fun ComposeContentTestRule.awaitCaptureSaved() {
    waitUntil(15_000) { onAllNodesWithText("Who will act on this?").fetchSemanticsNodes().isEmpty() }
}

internal fun ComposeContentTestRule.addContact(name: String) {
    onNodeWithTag("nav_contacts").performClick()
    waitUntil(15_000) { onAllNodesWithTag("add_contact").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag("add_contact").performClick()
    onNodeWithText("Name").performTextInput(name)
    onNodeWithText("Save contact").assertIsDisplayed().performClick()
    waitUntil(15_000) { onAllNodesWithText("Save contact").fetchSemanticsNodes().isEmpty() }
    waitUntil(15_000) { onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText(name).assertIsDisplayed()
}
