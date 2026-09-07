package com.kaavalan.note

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v2.1.1 smoke test for the Home empty state.
 *
 * Flow:
 *  1. Land on Home. The DataStore `hasSeenOnboarding` flag
 *     is shared across tests in this package, so the first
 *     test to run sees the OnboardingScreen and the rest
 *     land on Home. We tolerate both: if "Skip" is present,
 *     tap it; otherwise continue.
 *  2. The Quick note bar and "Import from contacts" button
 *     are visible.
 *  3. Tapping the Quick note bar opens the capture sheet,
 *     which renders a TextField for note input.
 */
@RunWith(AndroidJUnit4::class)
class HomeEmptyStateTest {

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
    fun home_quickNoteBar_opensCaptureSheet() {
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

        // Same post-onboarding race AddPersonFlowTest hit: the wait
        // above is satisfied by "Skip" alone, and dismissing
        // onboarding routes to Home through a DataStore write whose
        // emission waitForIdle() does not cover.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Add person")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // The Quick note bar is the persistent bottom-of-Home
        // Surface (R.string.note_bar_hint = "Quick note").
        composeRule.onNodeWithText("Quick note").assertIsDisplayed()
        // Run 34073735523 failed on the next line specifically. The
        // node EXISTS -- a miss reports "could not find any node"
        // instead -- so Home is composed and the empty state is the
        // rendered state; the button is simply laid out off screen.
        // "Import from contacts" is rendered in exactly one place,
        // the empty state's OutlinedButton (HomeScreen.kt:501), and
        // that Column is centred inside a Box with no scrolling, so
        // anything that overflows is clipped and genuinely
        // unreachable -- an app defect, not a test problem, if that
        // is what is happening. Telling the two apart needs the real
        // geometry, which nothing reports today. The assertion is
        // left exactly as strong; the numbers are attached to the
        // failure rather than guessed at.
        try {
            composeRule.onNodeWithText("Import from contacts").assertIsDisplayed()
        } catch (failure: AssertionError) {
            // The fetch has to be guarded. In run 34075300005 this
            // block threw its own "Failed: assertExists" because by
            // then the node was gone, which replaced the original
            // message instead of adding to it -- the diagnostic hid
            // the very thing it was added to report.
            val detail = runCatching {
                val button = composeRule.onNodeWithText("Import from contacts").fetchSemanticsNode()
                val root = composeRule.onRoot().fetchSemanticsNode()
                "import button boundsInRoot=${button.boundsInRoot} size=${button.size} " +
                    "| root boundsInRoot=${root.boundsInRoot} size=${root.size}"
            }.getOrElse { "no node to measure (${it.message})" }
            throw AssertionError("${failure.message} | $detail", failure)
        }

        // Open the capture sheet.
        composeRule.onNodeWithText("Quick note").performClick()
        composeRule.waitForIdle()

        // The capture sheet's primary input is an
        // OutlinedTextField labelled "Note"
        // (R.string.capture_sheet_text_label). The label is
        // the strongest match because it is the TextField's
        // label slot (not a placeholder, not a heading).
        // The sheet body is a Column with `verticalScroll`
        // (CaptureSheet.kt:237) and the Note field sits below the
        // header and below NoPeopleCard, which renders whenever the
        // user has no people -- exactly the state this test starts
        // in. Add the IME opening on top (the outer Column takes
        // `imePadding()`) and the field can sit under the fold, so
        // asserting it is displayed without scrolling to it asserts
        // something the sheet does not guarantee. Same defect, same
        // fix as the Settings sheet's section headers.
        composeRule.onNodeWithText("Note").performScrollTo().assertIsDisplayed()
    }
}
