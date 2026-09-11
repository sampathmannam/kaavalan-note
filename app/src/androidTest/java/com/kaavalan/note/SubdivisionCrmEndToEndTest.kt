package com.kaavalan.note

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.IdlingPolicies
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.TimeUnit
import com.kaavalan.note.data.instructions.InstructionJournal
import com.kaavalan.note.debug.WorkspaceTestEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The subdivision CRM, driven through the real UI on the task-owned debug emulator.
 *
 * Synthetic fixtures only. Nothing here touches a real contact, a real station name or a
 * real subdivision.
 *
 * The scenarios are the ones the unit tests cannot reach: that the three entry rows exist
 * where the design says they do, that a full-screen destination opens and Back returns,
 * that an officer can go from an empty subdivision to a recorded review without leaving
 * the app, and that a capture started from a matter really lands in that matter.
 */
class SubdivisionCrmEndToEndTest {

    @get:Rule(order = 0)
    val permissions = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * This suite runs on a software-rendered emulator, where a screen the size of the
     * review list settles well outside Espresso's default idle timeouts. Raise the
     * patience; every assertion below is unchanged.
     */
    @Before fun allowForASoftwareRenderedDevice() {
        IdlingPolicies.setMasterPolicyTimeout(5, TimeUnit.MINUTES)
        IdlingPolicies.setIdlingResourceTimeout(5, TimeUnit.MINUTES)
    }

    private fun graph() = EntryPointAccessors.fromApplication(
        compose.activity.applicationContext,
        WorkspaceTestEntryPoint::class.java,
    )

    private fun awaitTag(tag: String, timeoutMs: Long = 60_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitText(text: String, timeoutMs: Long = 60_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun back() {
        compose.onNodeWithTag("subdivision_back").performClick()
    }

    @Test
    fun everyTabOffersItsSubdivisionEntry_andEachDestinationOpensAndReturns() {
        compose.openWorkspace()

        // Today: the review entry, immediately below the top bar, with setup guidance.
        compose.onNodeWithTag("nav_today").performClick()
        awaitTag("entry_subdivision_review")
        compose.onNodeWithTag("entry_subdivision_review").assertIsDisplayed()
        compose.onNodeWithText("Set up your subdivision").assertIsDisplayed()
        compose.onNodeWithTag("entry_subdivision_review").performClick()
        awaitTag("subdivision_title")
        compose.onNodeWithText("Subdivision review").assertIsDisplayed()
        compose.onNodeWithText("Your private work record. Staff do not need an account.").assertIsDisplayed()
        back()
        awaitTag("entry_subdivision_review")

        // Instructions: Matters.
        compose.onNodeWithTag("nav_home").performClick()
        awaitTag("entry_matters")
        compose.onNodeWithText("Group related instructions").assertIsDisplayed()
        compose.onNodeWithTag("entry_matters").performClick()
        awaitTag("matters_list")
        compose.onNodeWithText("Group related instructions and their history.").assertIsDisplayed()
        back()
        awaitTag("entry_matters")

        // Contacts: Stations & staff.
        compose.onNodeWithTag("nav_contacts").performClick()
        awaitTag("entry_stations_staff")
        compose.onNodeWithText("Postings and responsibilities").assertIsDisplayed()
        compose.onNodeWithTag("entry_stations_staff").performClick()
        awaitTag("stations_list")
        compose.onNodeWithTag("segment_STATIONS").assertIsDisplayed()
        compose.onNodeWithTag("segment_STAFF").assertIsDisplayed()
        back()
        awaitTag("entry_stations_staff")

        // Still exactly three primary tabs.
        compose.onNodeWithTag("nav_today").assertIsDisplayed()
        compose.onNodeWithTag("nav_home").assertIsDisplayed()
        compose.onNodeWithTag("nav_contacts").assertIsDisplayed()
    }

    @Test
    fun setUpSubdivision_addStation_markStaff_addMatter_captureInContext_thenRecordReview() {
        compose.openWorkspace()
        val stationName = "QA Kalakad ${System.currentTimeMillis() % 100000}"
        val contactName = "QA Inspector Ramesh"
        val matterTitle = "QA sand mining inquiry"
        val noteText = "QA seize the two lorries at the quarry road"

        // 1. A contact to classify. Manual entry, no phone directory involved.
        compose.addContact(contactName)

        // 2. Set up the subdivision from the review screen.
        compose.onNodeWithTag("nav_today").performClick()
        awaitTag("entry_subdivision_review")
        compose.onNodeWithTag("entry_subdivision_review").performClick()
        awaitTag("review_edit_profile")
        compose.onNodeWithTag("review_edit_profile").performClick()
        awaitTag("profile_name")
        compose.onNodeWithTag("profile_name").performTextInput("QA Subdivision")
        compose.onNodeWithTag("profile_district").performTextInput("QA District")
        compose.onNodeWithTag("subdivision_editor_save").performClick()
        awaitText("QA Subdivision")
        compose.onNodeWithTag("review_profile_name").assertTextEquals("QA Subdivision")
        back()

        // 3. A station, then the contact becomes staff with responsibilities.
        compose.onNodeWithTag("nav_contacts").performClick()
        awaitTag("entry_stations_staff")
        compose.onNodeWithTag("entry_stations_staff").performClick()
        awaitTag("add_station")
        compose.onNodeWithTag("add_station").performClick()
        awaitTag("station_name")
        compose.onNodeWithTag("station_name").performTextInput(stationName)
        compose.onNodeWithTag("station_notes").performTextInput("QA coastal beat")
        compose.onNodeWithTag("subdivision_editor_save").performClick()
        awaitText(stationName)

        compose.onNodeWithTag("segment_STAFF").performClick()
        awaitTag("add_from_contacts")
        compose.onNodeWithTag("add_from_contacts").performClick()
        awaitTag("chooser_search")
        compose.onNodeWithTag("chooser_item_$contactName").performClick()
        awaitTag("staff_responsibilities")
        compose.onNodeWithTag("staff_is_staff").performClick()
        compose.onNodeWithTag("staff_station").performScrollTo().performClick()
        awaitTag("chooser_search")
        compose.onNodeWithTag("chooser_item_$stationName").performClick()
        compose.onNodeWithTag("staff_responsibilities").performScrollTo()
            .performTextInput("QA coastal beat, night patrol")
        compose.onNodeWithTag("subdivision_editor_save").performClick()
        awaitTag("staff_row_$contactName")
        back()

        val staff = runBlocking { graph().people().snapshot().single { it.name == contactName } }
        assertTrue("the contact must be classified as staff", staff.isStaff)
        assertTrue(staff.staffActive)
        assertEquals("QA coastal beat, night patrol", staff.responsibilities)
        assertNotNull("a staff member must have a stable station id", staff.stationId)

        // 4. A matter, then new work captured in its context.
        compose.onNodeWithTag("nav_home").performClick()
        awaitTag("entry_matters")
        compose.onNodeWithTag("entry_matters").performClick()
        awaitTag("add_matter")
        compose.onNodeWithTag("add_matter").performClick()
        awaitTag("matter_title")
        compose.onNodeWithTag("matter_title").performTextInput(matterTitle)
        compose.onNodeWithTag("matter_station").performScrollTo().performClick()
        awaitTag("chooser_search")
        compose.onNodeWithTag("chooser_item_$stationName").performClick()
        compose.onNodeWithTag("matter_reference").performScrollTo().performTextInput("QA/REF/1")
        compose.onNodeWithTag("subdivision_editor_save").performClick()
        awaitTag("matter_row_$matterTitle")
        compose.onNodeWithTag("matter_row_$matterTitle").performClick()
        awaitTag("matter_add_instruction")

        // Add instruction opens the existing capture sheet with the matter preselected.
        compose.onNodeWithTag("matter_add_instruction").performClick()
        awaitTag("capture_context")
        compose.onNodeWithTag("capture_context").assertIsDisplayed()
        awaitText("Note")
        compose.onNodeWithText("Note").performTextInput(noteText)
        // Capture pins Save outside the scrolling body, above the IME, so there is nothing
        // to scroll it into view - asking for that is what a user would never have to do.
        compose.onNodeWithText("Save").performClick()
        compose.awaitCaptureSaved()

        val created = runBlocking { graph().instructions().snapshot().single { it.rawText == noteText } }
        assertNotNull("the captured note must be filed in the matter", created.matterId)
        assertEquals(
            "and recorded at the matter's station",
            staff.stationId,
            created.stationId,
        )

        // 5. Back out of the matter - a child destination has Back, not the bottom nav -
        // and record a review of the whole subdivision.
        back()
        awaitTag("matter_row_$matterTitle")
        back()
        awaitTag("entry_matters")
        compose.onNodeWithTag("nav_today").performClick()
        awaitTag("entry_subdivision_review")
        compose.onNodeWithTag("entry_subdivision_review").performClick()
        awaitTag("review_summary")
        compose.onNodeWithTag("review_filter_OPEN").assertIsDisplayed()
        compose.onNodeWithTag("review_filter_READY_TO_VERIFY").assertIsDisplayed()
        compose.onNodeWithTag("review_filter_DEADLINE_PASSED").assertIsDisplayed()
        compose.onNodeWithTag("review_filter_NO_UPDATE_7_DAYS").assertIsDisplayed()
        compose.onNodeWithTag("review_filter_CHANGED_SINCE_LAST_REVIEW").assertIsDisplayed()

        // With no earlier review, the changed filter says so instead of inventing a date.
        compose.onNodeWithTag("review_filter_CHANGED_SINCE_LAST_REVIEW").performClick()
        awaitTag("review_no_prior")
        compose.onNodeWithText("No earlier review for this scope").assertIsDisplayed()
        compose.onNodeWithTag("review_filter_OPEN").performClick()

        // Scroll the lazy list itself, then let it settle before clicking. On a
        // software-rendered device the list is still laying out when performScrollToNode
        // returns, and a click injected at that moment lands on whatever occupied the
        // coordinate a frame earlier. Verified by hand: the button itself works.
        compose.onNodeWithTag("subdivision_review_list").performScrollToNode(hasTestTag("review_record"))
        compose.waitForIdle()
        android.os.SystemClock.sleep(1_000)
        compose.onNodeWithTag("review_record").assertIsEnabled().performClick()
        awaitTag("review_notes")
        compose.onNodeWithTag("review_scope").assertTextEquals("Whole subdivision")
        compose.onNodeWithTag("review_notes").performTextInput("QA monthly review note.")
        compose.onNodeWithTag("subdivision_editor_save").performClick()
        awaitText("At this review · 1 open · 0 ready to verify")

        val review = runBlocking { graph().subdivision().reviews().single() }
        assertEquals("Whole subdivision", review.scopeTitle)
        assertEquals(1, review.openCount)
        assertEquals(0, review.readyCount)
        assertEquals(
            "recording a review must not complete the instruction",
            "OPEN",
            runBlocking { graph().instructions().getById(created.id)?.status },
        )
    }

    @Test
    fun changeWorkContextFromInstructionDetail_journalsBothSidesAndSurvivesRecreation() {
        compose.openWorkspace()
        val first = "QA First station ${System.currentTimeMillis() % 100000}"
        val second = "QA Second station ${System.currentTimeMillis() % 100000}"
        val noteText = "QA verify the weighbridge records"

        compose.onNodeWithTag("nav_contacts").performClick()
        awaitTag("entry_stations_staff")
        compose.onNodeWithTag("entry_stations_staff").performClick()
        awaitTag("add_station")
        listOf(first, second).forEach { name ->
            compose.onNodeWithTag("add_station").performClick()
            awaitTag("station_name")
            compose.onNodeWithTag("station_name").performTextInput(name)
            compose.onNodeWithTag("subdivision_editor_save").performClick()
            awaitText(name)
        }
        back()

        compose.saveNote(noteText, "Assigned by me")
        compose.onNodeWithTag("nav_today").performClick()
        awaitText(noteText)
        compose.onNodeWithText(noteText).performClick()
        awaitTag("instruction_work_context")
        compose.onNodeWithText("No station recorded · No matter").assertIsDisplayed()

        compose.onNodeWithTag("detail_change_context").performScrollTo().performClick()
        awaitTag("context_station")
        compose.onNodeWithTag("context_station").performClick()
        awaitTag("chooser_search")
        compose.onNodeWithTag("chooser_item_$first").performClick()
        awaitTag("context_preview")
        compose.onNodeWithTag("subdivision_editor_save").performClick()
        awaitTag("instruction_work_context")

        var saved = runBlocking { graph().instructions().snapshot().single { it.rawText == noteText } }
        assertNotNull(saved.stationId)
        assertNull("a station-only context leaves the matter unset", saved.matterId)
        assertTrue(
            "the journal must name where the work moved to",
            InstructionJournal.decode(saved.updatesJson).any { it.text.contains(first) },
        )

        // Move it again; the earlier entry must still be there afterwards.
        compose.onNodeWithTag("detail_change_context").performScrollTo().performClick()
        awaitTag("context_station")
        compose.onNodeWithTag("context_station").performClick()
        awaitTag("chooser_search")
        compose.onNodeWithTag("chooser_item_$second").performClick()
        compose.onNodeWithTag("subdivision_editor_save").performClick()
        awaitTag("instruction_work_context")

        saved = runBlocking { graph().instructions().snapshot().single { it.rawText == noteText } }
        val journal = InstructionJournal.decode(saved.updatesJson)
        assertTrue("the first move is still recorded", journal.any { it.text.contains(first) })
        assertTrue("and so is the second", journal.any { it.text.contains(second) })

        // Recreation restores the detail the officer had open, by id, so the note is on
        // screen twice: in the restored sheet and in the list behind it. Assert on the
        // sheet, and on the context it came back with.
        compose.activityRule.scenario.recreate()
        awaitTag("instruction_work_context")
        compose.onNodeWithTag("instruction_work_context")
            .assert(hasAnyDescendant(hasText(second, substring = true)))
        compose.waitUntil(60_000) { compose.onAllNodesWithText(noteText).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun anArchiveBlockedByOpenWork_explainsTheNextActionInline() {
        compose.openWorkspace()
        val stationName = "QA Blocked station ${System.currentTimeMillis() % 100000}"
        val noteText = "QA open work at the blocked station"

        compose.onNodeWithTag("nav_contacts").performClick()
        awaitTag("entry_stations_staff")
        compose.onNodeWithTag("entry_stations_staff").performClick()
        awaitTag("add_station")
        compose.onNodeWithTag("add_station").performClick()
        awaitTag("station_name")
        compose.onNodeWithTag("station_name").performTextInput(stationName)
        compose.onNodeWithTag("subdivision_editor_save").performClick()
        awaitText(stationName)
        back()

        compose.saveNote(noteText, "Assigned by me")
        compose.onNodeWithTag("nav_today").performClick()
        awaitText(noteText)
        compose.onNodeWithText(noteText).performClick()
        awaitTag("detail_change_context")
        compose.onNodeWithTag("detail_change_context").performScrollTo().performClick()
        awaitTag("context_station")
        compose.onNodeWithTag("context_station").performClick()
        awaitTag("chooser_search")
        compose.onNodeWithTag("chooser_item_$stationName").performClick()
        compose.onNodeWithTag("subdivision_editor_save").performClick()
        awaitTag("instruction_work_context")
        compose.onNodeWithTag("nav_contacts").performClick()

        awaitTag("entry_stations_staff")
        compose.onNodeWithTag("entry_stations_staff").performClick()
        awaitTag("station_row_$stationName")
        compose.onNodeWithTag("station_row_$stationName").performClick()
        awaitTag("station_archive")
        compose.onNodeWithTag("station_archive").performScrollTo().performClick()

        awaitTag("subdivision_mutation_error")
        compose.onNodeWithTag("subdivision_mutation_error").assertIsDisplayed()
        compose.onNodeWithText(
            "This station still has open instructions. Complete them, or move them to another station first.",
        ).assertIsDisplayed()

        val station = runBlocking { graph().subdivision().stations().single { it.name == stationName } }
        assertFalse("nothing may have been archived", station.archived)
    }

    @Test
    fun theSubdivisionEntriesAreHiddenInThePrivateWorkspace() {
        compose.openWorkspace()
        runBlocking { graph().vault().setMode(com.kaavalan.note.data.vault.VaultMode.Hidden) }
        compose.onNodeWithTag("nav_today").performClick()
        compose.waitUntil(60_000) {
            compose.onAllNodesWithTag("entry_subdivision_review").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("nav_home").performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("entry_matters").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("nav_contacts").performClick()
        compose.waitUntil(60_000) {
            compose.onAllNodesWithTag("entry_stations_staff").fetchSemanticsNodes().isEmpty()
        }
        runBlocking { graph().vault().setMode(com.kaavalan.note.data.vault.VaultMode.Visible) }
        compose.onNodeWithTag("nav_today").performClick()
        awaitTag("entry_subdivision_review")
    }
}
