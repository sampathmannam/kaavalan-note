package com.kaavalan.note

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.kaavalan.note.debug.WorkspaceTestEntryPoint
import com.kaavalan.note.data.instructions.InstructionJournal
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic fixtures only. Run exclusively on the task-owned debug emulator. */
class FollowUpWorkspaceEndToEndTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun graph() = EntryPointAccessors.fromApplication(compose.activity.applicationContext, WorkspaceTestEntryPoint::class.java)

    @Test fun editUpdateVerifyUndoAndSearch_persistAcrossRecreation() {
        compose.openWorkspace()
        val text = "QA follow-up: confirm patrol deployment"
        compose.saveNote(text, "Assigned by me")
        compose.onNodeWithTag("nav_today").performClick()
        compose.onNodeWithText(text).assertIsDisplayed().performClick()
        compose.onNodeWithTag("detail_edit").performClick()
        compose.onNodeWithTag("edit_instruction_text").performTextReplacement("QA revised patrol deployment")
        compose.onNodeWithText("Pick date & time").performScrollTo().performClick()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithTag("workspace_editor_save").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("detail_add_update").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_add_update").performClick()
        compose.onNodeWithTag("instruction_update_text").performTextInput("Inspector confirmed deployment by wireless")
        compose.onNodeWithTag("update_progress").performScrollTo().performClick()
        compose.onNodeWithText("Ready to verify").performClick()
        compose.onNodeWithText("In 1 hour").performScrollTo().performClick()
        compose.onNodeWithTag("workspace_editor_save").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("detail_done").fetchSemanticsNodes().isNotEmpty() }
        val saved = runBlocking { graph().instructions().snapshot().single { it.rawText == "QA revised patrol deployment" } }
        assertEquals("REPORTED_DONE", saved.status)
        assertNotNull(saved.dueAtMs)
        assertNotNull(saved.deadlineAtMs)
        assertTrue(saved.deadlineAtMs!! > saved.dueAtMs!!)
        assertEquals(2, InstructionJournal.decode(saved.updatesJson).size)
        screenshot("01-instruction-updates")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("detail_done").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Verify & mark done").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(15_000) { runBlocking { graph().instructions().getById(saved.id)?.status == "REPORTED_DONE" } }
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithText("Search instructions and updates").performTextInput("wireless")
        compose.onNodeWithText("QA revised patrol deployment").assertIsDisplayed().performClick()
        compose.onNodeWithTag("detail_done").performClick()
        compose.waitUntil(15_000) { runBlocking { graph().instructions().getById(saved.id)?.status == "DONE" } }
        // All records is the default: the completed note remains findable by its update text.
        compose.onNodeWithText("QA revised patrol deployment").assertIsDisplayed()
        compose.onNodeWithTag("filter_OPEN").performClick()
        compose.onNodeWithText("No matching instructions").assertIsDisplayed()
        compose.onNodeWithTag("filter_CLOSED").performClick()
        compose.onNodeWithText("QA revised patrol deployment").assertIsDisplayed()
        screenshot("02-search-closed-update")
    }

    @Test fun contactMetadataAndSharedDetail_canBeEditedFromContactTimeline() {
        compose.openWorkspace()
        compose.addContact("QA Imported Inspector")
        compose.onNodeWithText("QA Imported Inspector").performClick()
        try {
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Edit contact").fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            screenshot("contact-navigation-failure")
            compose.onRoot().printToLog("ContactNavigation")
            throw failure
        }
        compose.onNodeWithText("Edit contact").performClick()
        compose.onNodeWithTag("edit_contact_rank").performTextInput("Inspector")
        compose.onNodeWithTag("edit_contact_station").performTextInput("North station")
        compose.onNodeWithTag("workspace_editor_save").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("workspace_editor_save").fetchSemanticsNodes().isEmpty() }
        val person = runBlocking { graph().contacts().snapshot().single { it.name == "QA Imported Inspector" } }
        assertEquals("Inspector", person.designation)
        assertEquals("North station", person.station)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("QA Imported Inspector").fetchSemanticsNodes().isNotEmpty() }
        screenshot("03-edited-contact")
        compose.onNodeWithText("Add instruction for QA Imported Inspector").performScrollTo().performClick()
        compose.onNodeWithText("Note").performTextInput("QA contact-linked station briefing")
        compose.onNode(hasText("Assigned by me") and isSelectable()).performScrollTo().performClick()
        compose.onNodeWithText("Save").assertIsDisplayed().performClick()
        compose.awaitCaptureSaved()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("QA contact-linked station briefing").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("QA contact-linked station briefing").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("detail_add_update").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_edit").assertIsDisplayed()
        compose.onNodeWithTag("detail_add_update").performClick()
        compose.onNodeWithTag("instruction_update_text").performTextInput("Contact confirmed the briefing time")
        compose.onNodeWithTag("workspace_editor_save").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("detail_add_update").fetchSemanticsNodes().isNotEmpty() }
        val linked = runBlocking { graph().instructions().snapshot().single { it.rawText == "QA contact-linked station briefing" } }
        assertEquals(person.id, linked.personId)
        assertEquals("Contact confirmed the briefing time", InstructionJournal.decode(linked.updatesJson).single().text)
    }

    @Test fun largeTextLandscape_updateSaveStaysAboveKeyboard() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val oldScale = shell("settings get system font_scale")
        try {
            compose.openWorkspace()
            compose.saveNote("QA accessible follow-up", "Assigned by me")
            compose.onNodeWithText("QA accessible follow-up").performClick()
            compose.onNodeWithTag("detail_add_update").performClick()
            shell("settings put system font_scale 1.5")
            compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(15_000) { compose.activity.resources.configuration.fontScale >= 1.5f &&
                compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithTag("instruction_update_text").performScrollTo().performTextInput("A readable update after the briefing")
            compose.waitUntil(15_000) { shell("dumpsys input_method").contains("mIsInputViewShown=true") }
            compose.onNodeWithTag("workspace_editor_save").assertIsDisplayed().assertIsEnabled()
            screenshot("04-large-text-landscape-update")
            compose.onNodeWithTag("workspace_editor_save").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("detail_add_update").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            shell(if (oldScale == "null" || oldScale.isBlank()) "settings delete system font_scale" else "settings put system font_scale $oldScale")
            compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "follow-up-review")
        check(directory.exists() || directory.mkdirs())
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
