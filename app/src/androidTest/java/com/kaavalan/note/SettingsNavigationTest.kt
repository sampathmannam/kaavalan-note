package com.kaavalan.note

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v2.1.1 smoke test for the Settings bottom sheet.
 *
 * Flow:
 *  1. Open Settings via the bottom-nav Settings tab.
 *  2. The sheet renders the section labels
 *     (Privacy, Data, About) as section headers.
 *  3. Close the sheet via the Close icon button.
 *  4. Home is restored.
 */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {

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
    fun bottomNav_settings_opensSheet_showsSections_andCloseReturnsToHome() {
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

        // Step 1: tap the Settings tab in the bottom nav.
        // The tab's icon contentDescription is the tab label
        // (R.string.tab_settings = "Settings").
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()

        // Step 2: the sheet renders three section headers.
        // They are Text widgets so we assert on their text.
        composeRule.onNodeWithText("Privacy").assertIsDisplayed()
        composeRule.onNodeWithText("Data").assertIsDisplayed()
        composeRule.onNodeWithText("About").assertIsDisplayed()

        // Step 3: close via the X icon (contentDescription
        // is R.string.settings_close = "Close settings").
        composeRule.onNodeWithContentDescription("Close settings").performClick()
        composeRule.waitForIdle()

        // Step 4: Home is back; the TopAppBar title
        // R.string.home_title = "People" is visible.
        composeRule.onNodeWithText("People").assertIsDisplayed()
    }
}
