package com.kaavalan.note.data.person

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.MatrixCursor
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import com.kaavalan.note.data.person.ContactSyncService.LoadResult
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ContactSyncServiceTest {
    private val resolver = mockk<ContentResolver>()
    private val context = mockk<Context>()
    private val service = ContactSyncService(context)
    private lateinit var names: MatrixCursor
    private lateinit var phones: MatrixCursor

    @Before fun setup() {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { context.contentResolver } returns resolver
        names = MatrixCursor(arrayOf(Contacts._ID, Contacts.DISPLAY_NAME_PRIMARY))
        phones = MatrixCursor(arrayOf(Phone.CONTACT_ID, Phone.NUMBER))
        every { resolver.query(Contacts.CONTENT_URI, any(), null, null, any()) } returns names
        every { resolver.query(Phone.CONTENT_URI, any(), null, null, any()) } returns phones
    }

    @Test fun `all 2000 contacts including the last are available without a cap`() = runBlocking {
        repeat(2_000) {
            names.addRow(arrayOf(it.toLong(), "Officer $it"))
            phones.addRow(arrayOf(it.toLong(), "555${it.toString().padStart(7, '0')}"))
        }
        val result = service.fetchContactCandidates() as LoadResult.Loaded
        assertEquals(2_000, result.contacts.size)
        assertEquals("Officer 1999", result.contacts.last().displayName)
        assertEquals(2_000, result.contacts.map { it.key }.distinct().size)
        assertTrue(names.isClosed)
        assertTrue(phones.isClosed)
    }

    @Test fun `name-only contacts and numbers without a name are not discarded`() = runBlocking {
        names.addRow(arrayOf(1L, "  Name only  "))
        names.addRow(arrayOf(2L, null))
        phones.addRow(arrayOf(2L, " 5550100 "))
        val result = (service.fetchContactCandidates() as LoadResult.Loaded).contacts
        assertEquals("Name only", result[0].displayName)
        assertEquals("", result[0].phone)
        assertEquals("5550100", result[1].displayName)
    }

    @Test fun `shared numbers preserve people and formatting duplicates collapse only within one contact`() = runBlocking {
        names.addRow(arrayOf(1L, "First officer"))
        names.addRow(arrayOf(2L, "Second officer"))
        phones.addRow(arrayOf(1L, "+91 555-0100"))
        phones.addRow(arrayOf(1L, "+91 (555) 0100"))
        phones.addRow(arrayOf(1L, "5550200"))
        phones.addRow(arrayOf(2L, "+91 555-0100"))
        val result = (service.fetchContactCandidates() as LoadResult.Loaded).contacts
        assertEquals(3, result.size)
        assertEquals(3, result.map { it.key }.distinct().size)
        assertEquals(listOf("First officer", "First officer", "Second officer"), result.map { it.displayName })
    }

    @Test fun `blank and orphan phone rows do not hide a contact or add a phantom person`() = runBlocking {
        names.addRow(arrayOf(1L, "Officer"))
        phones.addRow(arrayOf(1L, "  "))
        phones.addRow(arrayOf(2L, "5550200"))
        val result = (service.fetchContactCandidates() as LoadResult.Loaded).contacts
        assertEquals(1, result.size)
        assertEquals("", result.single().phone)
    }

    @Test fun `denial is not an empty directory and does not query provider`() = runBlocking {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
        assertEquals(LoadResult.PermissionRequired, service.fetchContactCandidates())
        verify { resolver wasNot Called }
    }

    @Test fun `permission revoked during reading returns permission state`() = runBlocking {
        every { resolver.query(Contacts.CONTENT_URI, any(), null, null, any()) } throws SecurityException()
        assertEquals(LoadResult.PermissionRequired, service.fetchContactCandidates())
    }

    @Test fun `unavailable provider is a retryable failure not empty success`() = runBlocking {
        every { resolver.query(Contacts.CONTENT_URI, any(), null, null, any()) } returns null
        assertEquals(LoadResult.Failed, service.fetchContactCandidates())
    }

    @Test fun `failure loading phones does not publish a misleading partial directory`() = runBlocking {
        names.addRow(arrayOf(1L, "Officer"))
        every { resolver.query(Phone.CONTENT_URI, any(), null, null, any()) } throws IllegalStateException("private provider detail")
        assertEquals(LoadResult.Failed, service.fetchContactCandidates())
        assertTrue(names.isClosed)
    }

    @Test fun `empty provider is a successful empty directory`() = runBlocking {
        assertEquals(LoadResult.Loaded(emptyList()), service.fetchContactCandidates())
    }

    @Test fun `provider queries run off the main thread`() = runBlocking {
        every { resolver.query(Contacts.CONTENT_URI, any(), null, null, any()) } answers {
            assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
            names
        }
        assertTrue(service.fetchContactCandidates() is LoadResult.Loaded)
    }

    @Test fun `cancellation is not swallowed as a provider failure`() {
        every { resolver.query(Contacts.CONTENT_URI, any(), null, null, any()) } throws CancellationException()
        assertThrows(CancellationException::class.java) { runBlocking { service.fetchContactCandidates() } }
    }

    @Test fun `search handles case whitespace Tamil and formatted phone numbers`() {
        val candidate = ContactSyncService.ContactCandidate("1", "Inspector தமிழ்", "+91 (555) 010-0123")
        assertTrue(candidate.matches(" inspector "))
        assertTrue(candidate.matches("தமிழ்"))
        assertTrue(candidate.matches("5550100123"))
        assertTrue(candidate.matches("555 010"))
        assertTrue(candidate.matches(""))
        assertFalse(candidate.matches("missing"))
        assertFalse(candidate.matches("Officer 555"))
    }
}
