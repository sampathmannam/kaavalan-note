package com.kaavalan.note

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

internal fun ComposeContentTestRule.openWorkspace() {
    // Android Test Orchestrator starts a fresh app process for every method.
    // On a cold/loaded emulator the first semantics read can happen before
    // MainActivity calls setContent; Compose throws instead of returning an
    // empty collection. Treat that exact startup state as "not ready yet" so
    // waitUntil can do the retry it was intended to do.
    waitUntil(30_000) {
        runCatching {
            onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithTag("workspace_title").fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)
    }
    if (onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty()) onNodeWithText("Skip").performClick()
    waitUntil(30_000) {
        runCatching {
            onAllNodesWithTag("workspace_title").fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)
    }
    // Wait for on-screen controls, not only published semantics during startup.
    waitUntil(15_000) { runCatching { onNodeWithTag("capture_open").assertIsDisplayed() }.isSuccess }
    onNodeWithTag("capture_open").assertIsDisplayed()
}

internal fun ComposeContentTestRule.chooseDisplayTheme(label: String) {
    onNodeWithContentDescription("Settings").performClick()
    onNodeWithText("Display theme").performScrollTo().performClick()
    onNodeWithText(label).performClick()
    onNodeWithContentDescription("Close settings").performClick()
    waitForIdle()
}

internal fun ComposeContentTestRule.saveNote(text: String, responsibility: String? = null) {
    onNodeWithTag("capture_open").performClick()
    waitUntil(15_000) { onAllNodesWithText("Note").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Note").performTextInput(text)
    // Match the selectable responsibility chip, not identical explanatory text behind the sheet.
    responsibility?.let { onNode(hasText(it) and isSelectable()).performScrollTo().performClick() }
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
