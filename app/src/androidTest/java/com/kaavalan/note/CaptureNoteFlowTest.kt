package com.kaavalan.note

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v2.1.1 smoke test for the capture-note happy path.
 *
 * Flow:
 *  1. Add a person (the capture-sheet Save button is gated
 *     on hasPeople — see CaptureSheet.PrimaryAction).
 *  2. Open the capture sheet from the Quick note bar.
 *  3. Type a unique note title into the labelled TextField.
 *  4. Tap Save.
 *  5. The new instruction lands in the Outbox (the
 *     R.string.hierarchy_section_outbox section of the
 *     home list) — assert the title is visible.
 */
@RunWith(AndroidJUnit4::class)
class CaptureNoteFlowTest {

    // v2.1.2 (test-infra): grant POST_NOTIFICATIONS before MainActivity
    // launches.
    //
    // MainScaffold's LaunchedEffect(Unit) unconditionally fires the
    // POST_NOTIFICATIONS runtime-permission request on every cold launch
    // on API 33+ (see MainActivity.requestPostNotifications /
    // onRequestNotificationsPermission). Without a pre-grant, the system
    // permission dialog pops up mid-test and steals window focus from
    // MainActivity -- confirmed via logcat: the Activity goes
    // RESUMED -> PAUSED -> STOPPED -> DESTROYED while androidx.test's
    // InstrumentationActivityInvoker$EmptyActivity becomes the foreground
    // window trying (and failing, on this harness) to recover focus. The
    // visible symptom was every one of the six Compose UI smoke tests
    // failing with "Assert failed: The component is not displayed!" or
    // "No compose hierarchies found" -- confirmed reproducible on
    // unmodified `main` too, so this was a pre-existing gap in the test
    // suite, not a regression. GrantPermissionRule runs before the
    // Activity under test launches, so the permission is already held
    // and the dialog never appears. It is a documented no-op on API <33
    // (androidx.test.rule.GrantPermissionRule javadoc), so this rule is
    // safe on every minSdk this project supports.
    // v2.1.2 correction: explicit @Rule(order=) is required here.
    // JUnit4 does NOT guarantee rule application order from
    // declaration order alone -- without `order`, logcat showed
    // GrantPermissionRule's UiAutomationPermGranter attempting the
    // grant *after* ActivityTaskManager had already displayed
    // MainActivity, i.e. after the app's own LaunchedEffect had
    // already raced it to request the same permission and shown
    // the system dialog anyway. `order = 0` makes permissionRule
    // the outer rule, so its grant runs and completes before
    // composeRule (order = 1) launches the Activity.
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun addPerson_openCapture_typeAndSave_persistsNoteInOutbox() {
        // Tolerate either entry point: fresh install (Skip
        // visible) or post-onboarding (Skip gone).
        // v2.1.2 (test-infra): wait for routing to actually resolve
        // before touching anything. MainActivity decides Onboarding vs
        // Home from a DataStore read (hasSeenOnboarding), which is async
        // and outside what waitForIdle() tracks (Compose reports "idle"
        // correctly while genuinely waiting on external data -- there is
        // nothing scheduled to recompose yet). A fast run can otherwise
        // race ahead of that read and find neither "Skip" nor the FAB,
        // observed directly: home_fab_openAddPerson_saveNewPerson failed
        // to find ContentDescription="Add person" specifically on a run
        // where the whole test completed in 3.4s, far faster than
        // MainActivity's own logcat-measured cold-start-to-displayed
        // time elsewhere in this suite. Wait for either stable outcome
        // (Skip = onboarding, the FAB = Home; the FAB's
        // contentDescription="Add person" is unconditional -- see
        // HomeScreen's FloatingActionButton, not just the EmptyState's
        // button) before proceeding.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription("Add person").fetchSemanticsNodes().isNotEmpty()
        }
        runCatching { composeRule.onNodeWithText("Skip").performClick() }
        composeRule.waitForIdle()

        // Step 1: add a person so the capture sheet's Save
        // button is enabled (it is gated on hasPeople).
        composeRule.onNodeWithContentDescription("Add person").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Name").performTextInput("CaptureTest Person")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitForIdle()

        // Step 2: open the capture sheet via the Quick note bar.
        composeRule.onNodeWithText("Quick note").assertIsDisplayed()
        composeRule.onNodeWithText("Quick note").performClick()
        composeRule.waitForIdle()

        // Step 3: type a unique title into the OutlinedTextField
        // labelled "Note" (R.string.capture_sheet_text_label).
        // The capture sheet auto-saves the first line as the
        // instruction title, the full text as rawText.
        val uniqueNote = "QA smoke note 1742"
        composeRule.onNodeWithText("Note").performTextInput(uniqueNote)
        composeRule.waitForIdle()

        // Step 4: tap the capture sheet's Save button.
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitForIdle()

        // Step 5: the new instruction is rendered in the
        // Outbox section (R.string.hierarchy_section_outbox).
        // The Outbox only appears when there is at least one
        // outgoing instruction; the unique title is the
        // strongest signal that the save persisted.
        //
        // v2.1.2 (test-infra): bounded wait before asserting, same
        // reasoning as AddPersonFlowTest -- the capture-sheet Save
        // handler's Room insert (Dispatchers.IO) and the resulting
        // Outbox Flow re-collection are not covered by
        // waitForIdle()'s Compose-only idle tracking. 10s ceiling --
        // see the matching note in AddPersonFlowTest.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(uniqueNote).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Outbox").assertIsDisplayed()
        composeRule.onNodeWithText(uniqueNote).assertIsDisplayed()
    }
}
