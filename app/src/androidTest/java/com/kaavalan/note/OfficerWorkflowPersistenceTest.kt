package com.kaavalan.note

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.rule.GrantPermissionRule
import com.kaavalan.note.debug.WorkspaceTestEntryPoint
import com.kaavalan.note.data.work.WorkManagerInitializer
import com.kaavalan.note.data.reminder.WorkManagerReminderScheduler
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Compose → ViewModel → encrypted Room → WorkManager verification. */
class OfficerWorkflowPersistenceTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun receivedInstruction_keepsContactResponsibilityAndReminder_andDoneCancelsWork() {
        compose.openWorkspace()
        compose.addContact("Inspector QA")
        compose.onNodeWithTag("capture_open").performClick()
        val text = "Prepare security deployment for review"
        compose.onNodeWithText("Note").performTextInput(text)
        compose.onNodeWithText("Received").performScrollTo().performClick()
        compose.onNodeWithText("Link a contact (optional)").performScrollTo().performClick()
        compose.onNodeWithTag("capture_contact_Inspector QA").performClick()
        compose.onNodeWithText("Tomorrow at 9:00").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.awaitCaptureSaved()

        val app = compose.activity.applicationContext
        val graph = EntryPointAccessors.fromApplication(app, WorkspaceTestEntryPoint::class.java)
        val item = runBlocking { graph.instructions().snapshot().single { it.rawText == text } }
        val contact = runBlocking { graph.contacts().snapshot().single { it.name == "Inspector QA" } }
        assertEquals("INCOMING", item.direction)
        assertEquals(contact.id, item.personId)
        assertNotNull(item.dueAtMs)
        assertEquals(java.time.Instant.ofEpochMilli(item.dueAtMs!!).toString(), item.dueAt)
        val work = WorkManagerInitializer.get(app)
        val workName = WorkManagerReminderScheduler.workName(item.id)
        compose.waitUntil(15_000) { work.getWorkInfosForUniqueWork(workName).get().any { !it.state.isFinished } }

        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("workspace_title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag("filter_RECEIVED").performScrollTo().performClick()
        compose.onNodeWithText(text).performClick()
        compose.onNodeWithTag("detail_done").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            runBlocking { graph.instructions().getById(item.id)?.status == "DONE" } &&
                work.getWorkInfosForUniqueWork(workName).get().all { it.state.isFinished }
        }
    }

    @Test fun sharedTextFromContacts_opensTheSameCaptureAndPreservesTheDraft() {
        compose.openWorkspace()
        val activity = compose.activity
        val launcherIntent = Intent(activity.intent)
        try {
        compose.onNodeWithTag("nav_contacts").performClick()
        compose.onNodeWithTag("capture_open").performClick()
        compose.onNodeWithText("Note").performTextInput("Existing draft")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnUiThread {
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Shared duty update")
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Existing draft\n\nShared duty update").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Save").performClick()
        compose.awaitCaptureSaved()
        compose.onNodeWithTag("workspace_title").assertTextEquals("Contacts")
        } finally {
            // MainActivity correctly keeps the latest inbound intent. Restore the launch
            // identity only for ActivityScenario's lifecycle-matching teardown.
            compose.runOnUiThread { activity.intent = launcherIntent }
        }
    }
}
