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
 * v2.1.1 smoke test for the AddPerson flow.
 *
 * Flow:
 *  1. Open the AddPersonSheet via the Home FAB.
 *  2. Type a name into the first TextField (R.string.person_name).
 *  3. Tap Save. The Home screen re-renders with the new row.
 */
@RunWith(AndroidJUnit4::class)
class AddPersonFlowTest {

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
    fun home_fab_openAddPerson_saveNewPerson() {
        // Tolerate either entry point: fresh install (Skip
        // visible) or post-onboarding (Skip gone). The
        // DataStore flag is shared across tests in this
        // package; once OnboardingFlowTest has run, the
        // Skip button disappears.
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

        // The Home FAB has contentDescription="Add person"
        // (R.string.home_add_person). On the empty state, the
        // same text is also rendered as a Button label, so we
        // target the FAB by its icon contentDescription to
        // disambiguate.
        composeRule.onNodeWithContentDescription("Add person")
            .performClick()
        composeRule.waitForIdle()

        // The first OutlinedTextField is the Name field.
        // Save is disabled until Name is non-blank.
        composeRule.onNodeWithText("Name").performTextInput("QA Person")
        composeRule.waitForIdle()

        // The AddPersonSheet's primary Button reads "Save"
        // (R.string.person_save). The capture sheet also has
        // a "Save" button; here we are in the AddPersonSheet,
        // so the only "Save" on screen is the one we want.
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitForIdle()

        // Home re-renders with the new person row once the save
        // round-trip completes.
        //
        // v2.1.2 (test-infra): HomeScreen's onSave handler is
        // `scope.launch { viewModel.createPerson(...) }` --
        // rememberCoroutineScope().launch, not a suspend call
        // composeRule awaits directly. waitForIdle() settles once
        // that launch dispatches and the immediately-visible frame
        // stabilizes; it does not know to wait for the Room INSERT
        // underneath createPerson (dispatched to Dispatchers.IO) or
        // for the Home list's Flow to re-collect with the new row.
        // Asserting immediately after was a real, deterministic
        // test/app synchronization gap -- not machine load -- and is
        // the documented pattern for it
        // (developer.android.com/develop/ui/compose/testing bounded
        // wait for async state). 10s ceiling: this app's own
        // SQLCipher passphrase/key-derivation cost is documented
        // elsewhere in the codebase as a real, sometimes-noticeable
        // overhead (Argon2id KDF), so a single Room insert isn't
        // free; 10s stays well short of Espresso's own default
        // idling timeout while giving that overhead real room.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("QA Person").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("QA Person").assertIsDisplayed()
    }
}
