package com.kaavalan.note

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.kaavalan.note.data.person.ContactSyncService
import com.kaavalan.note.debug.WorkspaceTestEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Uses synthetic provider contacts on an isolated emulator, never a physical address book. */
class PhoneContactImportTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val created = mutableListOf<Uri>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun seed(name: String, vararg numbers: String) {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Synthetic contacts require an emulator" }
        check(instrumentation.targetContext.packageName.endsWith(".debug.officer")) { "Use the isolated QA application" }
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, null).withValue(RawContacts.ACCOUNT_NAME, null)
                .withValue(RawContacts.AGGREGATION_MODE, RawContacts.AGGREGATION_MODE_DISABLED).build(),
            ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build(),
        )
        numbers.forEach { number ->
            operations.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, number).withValue(Phone.TYPE, Phone.TYPE_WORK).build())
        }
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS)
        try {
            created.add(requireNotNull(instrumentation.targetContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)[0].uri))
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    @After fun removeOnlyOurSyntheticContacts() {
        if (created.isEmpty()) return
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS)
        try {
            created.forEach { uri -> instrumentation.targetContext.contentResolver.delete(
                ContentUris.withAppendedId(RawContacts.CONTENT_URI, ContentUris.parseId(uri)).buildUpon()
                    .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(), null, null) }
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    @Test fun searchBeyondFifty_importsExactNumber_andSurvivesRecreation_withoutChangingPhoneContacts() {
        repeat(65) { seed("Import QA ${it.toString().padStart(3, '0')}", "555${it.toString().padStart(7, '0')}") }
        seed("Import QA name only தமிழ்")
        seed("Import QA shared one", "5550200000", "5550200001")
        seed("Import QA shared two", "5550200000")
        val service = ContactSyncService(instrumentation.targetContext)
        val before = runBlocking { service.fetchContactCandidates() } as ContactSyncService.LoadResult.Loaded
        assertEquals(69, before.contacts.count { it.displayName.startsWith("Import QA") })

        compose.openWorkspace()
        compose.onNodeWithTag("nav_contacts").performClick()
        openPicker()
        compose.onNodeWithTag("phone_contacts_search").performTextInput("5550000064")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Import QA 064").fetchSemanticsNodes().isNotEmpty() }
        screenshot("beyond-fifty")
        compose.onNodeWithText("Import QA 064").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("phone_contacts_list").fetchSemanticsNodes().isEmpty() }
        val graph = EntryPointAccessors.fromApplication(compose.activity.applicationContext, WorkspaceTestEntryPoint::class.java)
        val saved = runBlocking { graph.contacts().snapshot().single { it.name == "Import QA 064" } }
        assertEquals("5550000064", saved.phone)
        compose.activityRule.scenario.recreate()
        compose.openWorkspace()
        compose.onNodeWithTag("nav_contacts").performClick()
        assertEquals(saved.id, runBlocking { graph.contacts().snapshot().single { it.name == "Import QA 064" }.id })

        openPicker()
        compose.onNodeWithTag("phone_contacts_search").performTextInput("Import QA name only")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Import QA name only தமிழ்").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("No phone number").assertIsDisplayed()
        compose.onNodeWithText("Import QA name only தமிழ்").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("phone_contacts_list").fetchSemanticsNodes().isEmpty() }
        assertNull(runBlocking { graph.contacts().snapshot().single { it.name == "Import QA name only தமிழ்" }.phone })

        openPicker()
        compose.onNodeWithTag("phone_contacts_search").performTextInput("5550200000")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Import QA shared two").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Import QA shared one").assertIsDisplayed()
        compose.onNodeWithText("Import QA shared two").assertIsDisplayed()
        screenshot("shared-number")
        compose.onNodeWithTag("phone_contacts_search").performTextReplacement("No such officer")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("No contacts match this search. Try another name or phone number.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before, runBlocking { service.fetchContactCandidates() })
        compose.onNodeWithText("Close").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("phone_contacts_list").fetchSemanticsNodes().isEmpty() }
    }

    private fun openPicker() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Import from phone contacts").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Import from phone contacts").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("phone_contacts_search").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        android.os.SystemClock.sleep(500)
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: File(instrumentation.targetContext.getExternalFilesDir(null), "ui-review")
        check(directory.exists() || directory.mkdirs())
        // Diagnostic artifact only: emulator capture can be unavailable even when Compose
        // can inspect and operate the UI. The import/persistence assertions must still run.
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: run {
            android.util.Log.w("PhoneContactImportTest", "Screenshot unavailable: $name")
            return
        }
        File(directory, "contact-import-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
