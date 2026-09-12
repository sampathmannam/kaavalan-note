package com.kaavalan.note

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.IdlingPolicies
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.TimeUnit
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Reproducible screenshots of the subdivision record for human review.
 *
 * Synthetic fixtures only — no real station, officer, subdivision or phone number appears
 * here. The point is to look at the screens on a real device rather than infer them from
 * Kotlin: empty states, a populated list, a detail, an editor, a recorded review, dark
 * mode and a large system font.
 */
class SubdivisionVisualReviewTest {

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

    private val station = "QA Kalakad"
    private val officer = "QA Inspector Ramesh"
    private val matter = "QA sand mining inquiry"

    private fun awaitTag(tag: String, timeoutMs: Long = 60_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitText(text: String, timeoutMs: Long = 60_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun back() = compose.onNodeWithTag("subdivision_back").performClick()

    @Test fun subdivisionRecord_emptyPopulatedAndDark_visualReview() {
        compose.openWorkspace()

        // 1. The three entry rows, and every destination while it is still empty.
        compose.onNodeWithTag("nav_today").performClick()
        awaitTag("entry_subdivision_review")
        screenshot("01-today-entry-before-setup")
        compose.onNodeWithTag("entry_subdivision_review").performClick()
        awaitTag("subdivision_title")
        screenshot("02-review-empty")
        back()

        compose.onNodeWithTag("nav_contacts").performClick()
        awaitTag("entry_stations_staff")
        compose.onNodeWithTag("entry_stations_staff").performClick()
        awaitTag("stations_list")
        screenshot("03-stations-empty")
        compose.onNodeWithTag("segment_STAFF").performClick()
        awaitTag("staff_list")
        screenshot("04-staff-empty")
        back()

        compose.onNodeWithTag("nav_home").performClick()
        awaitTag("entry_matters")
        compose.onNodeWithTag("entry_matters").performClick()
        awaitTag("matters_list")
        screenshot("05-matters-empty")
        back()

        // 2. Set the subdivision up.
        compose.onNodeWithTag("nav_today").performClick()
        awaitTag("entry_subdivision_review")
        compose.onNodeWithTag("entry_subdivision_review").performClick()
        awaitTag("review_edit_profile")
        compose.onNodeWithTag("review_edit_profile").performClick()
        awaitTag("profile_name")
        compose.onNodeWithTag("profile_name").performTextInput("QA Subdivision")
        compose.onNodeWithTag("profile_district").performTextInput("QA District")
        screenshot("06-profile-editor")
        compose.onNodeWithTag("subdivision_editor_save").performClick()
        awaitText("QA Subdivision")
        back()

        // 3. A station, and a contact classified as staff.
        compose.addContact(officer)
        compose.onNodeWithTag("nav_contacts").performClick()
        awaitTag("entry_stations_staff")
        compose.onNodeWithTag("entry_stations_staff").performClick()
        awaitTag("add_station")
        compose.onNodeWithTag("add_station").performClick()
        awaitTag("station_name")
        compose.onNodeWithTag("station_name").performTextInput(station)
        compose.onNodeWithTag("station_notes").performTextInput("QA coastal beat")
        screenshot("07-station-editor")
        compose.onNodeWithTag("subdivision_editor_save").performClick()
        awaitText(station)
        screenshot("08-stations-populated")

        compose.onNodeWithTag("segment_STAFF").performClick()
        awaitTag("add_from_contacts")
        compose.onNodeWithTag("add_from_contacts").performClick()
        awaitTag("chooser_search")
        screenshot("09-contact-chooser")
        compose.onNodeWithTag("chooser_item_$officer").performClick()
        awaitTag("staff_responsibilities")
        compose.onNodeWithTag("staff_is_staff").performClick()
        compose.onNodeWithTag("staff_station").performScrollTo().performClick()
        awaitTag("chooser_search")
        compose.onNodeWithTag("chooser_item_$station").performClick()
        compose.onNodeWithTag("staff_responsibilities").performScrollTo()
            .performTextInput("QA coastal beat, night patrol")
        screenshot("10-staff-editor")
        compose.onNodeWithTag("subdivision_editor_save").performClick()
        awaitTag("staff_row_$officer")
        screenshot("11-staff-populated")
        compose.onNodeWithTag("staff_row_$officer").performClick()
        awaitTag("subdivision_title")
        screenshot("12-staff-detail")
        back()
        back()

        // 4. A matter, and work captured in its context.
        compose.onNodeWithTag("nav_home").performClick()
        awaitTag("entry_matters")
        compose.onNodeWithTag("entry_matters").performClick()
        awaitTag("add_matter")
        compose.onNodeWithTag("add_matter").performClick()
        awaitTag("matter_title")
        compose.onNodeWithTag("matter_title").performTextInput(matter)
        compose.onNodeWithTag("matter_station").performScrollTo().performClick()
        awaitTag("chooser_search")
        compose.onNodeWithTag("chooser_item_$station").performClick()
        compose.onNodeWithTag("matter_reference").performScrollTo().performTextInput("QA/REF/1")
        screenshot("13-matter-editor")
        compose.onNodeWithTag("subdivision_editor_save").performClick()
        awaitTag("matter_row_$matter")
        screenshot("14-matters-populated")
        compose.onNodeWithTag("matter_row_$matter").performClick()
        awaitTag("matter_add_instruction")
        screenshot("15-matter-detail")
        compose.onNodeWithTag("matter_add_instruction").performClick()
        awaitTag("capture_context")
        screenshot("16-capture-in-matter-context")
        awaitText("Note")
        compose.onNodeWithText("Note").performTextInput("QA seize the two lorries at the quarry road")
        compose.onNodeWithText("Save").performClick()
        compose.awaitCaptureSaved()

        // 5. The review, its filters, and a recorded review. Back out of the matter first:
        // a child destination has Back, not the bottom nav.
        back()
        awaitTag("matter_row_$matter")
        back()
        awaitTag("entry_matters")
        compose.onNodeWithTag("nav_today").performClick()
        awaitTag("entry_subdivision_review")
        screenshot("17-today-entry-after-setup")
        compose.onNodeWithTag("entry_subdivision_review").performClick()
        awaitTag("review_summary")
        screenshot("18-review-populated")
        compose.onNodeWithTag("review_filter_CHANGED_SINCE_LAST_REVIEW").performClick()
        awaitTag("review_no_prior")
        screenshot("19-review-no-prior-review")
        compose.onNodeWithTag("review_filter_OPEN").performClick()
        compose.onNodeWithTag("subdivision_review_list").performScrollToNode(hasTestTag("review_record"))
        compose.waitForIdle()
        android.os.SystemClock.sleep(1_000)
        compose.onNodeWithTag("review_record").performClick()
        awaitTag("review_notes")
        compose.onNodeWithTag("review_notes").performTextInput("QA monthly review note.")
        screenshot("20-review-editor")
        compose.onNodeWithTag("subdivision_editor_save").performClick()
        awaitText("At this review · 1 open · 0 ready to verify")
        screenshot("21-review-recorded")

        // 6. The same record in dark mode.
        back()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Display theme").performScrollTo().performClick()
        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithContentDescription("Close settings").performClick()
        compose.onNodeWithTag("nav_today").performClick()
        awaitTag("entry_subdivision_review")
        compose.onNodeWithTag("entry_subdivision_review").performClick()
        awaitTag("review_summary")
        screenshot("22-review-dark")
        back()
        compose.onNodeWithTag("nav_contacts").performClick()
        awaitTag("entry_stations_staff")
        compose.onNodeWithTag("entry_stations_staff").performClick()
        awaitTag("stations_list")
        screenshot("23-stations-dark")
    }

    @Test fun subdivisionRecord_atOneAndAHalfTimesSystemFont_staysReachable() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command),
        ).bufferedReader().use { it.readText().trim() }
        val oldScale = shell("settings get system font_scale")
        try {
            compose.openWorkspace()
            compose.onNodeWithTag("nav_contacts").performClick()
            awaitTag("entry_stations_staff")
            compose.onNodeWithTag("entry_stations_staff").performClick()
            awaitTag("add_station")
            compose.onNodeWithTag("add_station").performClick()
            awaitTag("station_name")
            compose.onNodeWithTag("station_name").performTextInput(station)
            compose.onNodeWithTag("subdivision_editor_save").performClick()
            awaitText(station)

            shell("settings put system font_scale 1.5")
            compose.waitUntil(60_000) { compose.activity.resources.configuration.fontScale >= 1.5f }
            awaitTag("stations_list")
            screenshot("24-stations-large-font")

            // The empty state of a list is the case that used to be measured with an
            // unbounded height; check it renders and scrolls at a large font too.
            compose.onNodeWithTag("segment_STAFF").performClick()
            awaitTag("staff_list")
            screenshot("25-staff-empty-large-font")

            // An editor at a large font must still show an enabled, tappable Save.
            compose.onNodeWithTag("segment_STATIONS").performClick()
            awaitTag("add_station")
            compose.onNodeWithTag("add_station").performClick()
            awaitTag("station_name")
            compose.onNodeWithTag("subdivision_editor_save").assertIsDisplayed().assertIsEnabled()
            screenshot("26-station-editor-large-font")
        } finally {
            shell(
                if (oldScale == "null" || oldScale.isBlank()) "settings delete system font_scale"
                else "settings put system font_scale $oldScale",
            )
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        android.os.SystemClock.sleep(500)
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: File(instrumentation.targetContext.getExternalFilesDir(null), "ui-review")
        check(directory.exists() || directory.mkdirs())
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "subdivision-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
