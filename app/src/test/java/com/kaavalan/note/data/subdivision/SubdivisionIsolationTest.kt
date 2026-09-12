package com.kaavalan.note.data.subdivision

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.RoomPersonRepository
import com.kaavalan.note.data.vault.VaultModeHolder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two independent installations.
 *
 * The product promise is that a friend who installs the app gets a blank profile and their
 * own private local data - no shared account, no shared database, nothing of the first
 * officer's in their app. Two separate Room databases in one test is the closest
 * reproduction of that: whatever the first officer records, the second must not see, and
 * the second's onboarding must contain none of the first officer's identity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SubdivisionIsolationTest {

    private lateinit var officerDb: AppDatabase
    private lateinit var friendDb: AppDatabase
    private lateinit var officer: SubdivisionRepository
    private lateinit var friend: SubdivisionRepository
    private val vault = VaultModeHolder()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        officerDb = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        friendDb = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        officer = SubdivisionRepository(officerDb, vault)
        friend = SubdivisionRepository(friendDb, vault)
    }

    @After
    fun tearDown() {
        officerDb.close()
        friendDb.close()
    }

    @Test
    fun `a friend's fresh installation is completely blank`() = runTest {
        assertNull(friendDb.subdivisionDao().profile("visible"))
        assertTrue(friendDb.subdivisionDao().stations().isEmpty())
        assertTrue(friendDb.subdivisionDao().matters().isEmpty())
        assertTrue(friendDb.subdivisionDao().postings().isEmpty())
        assertTrue(friendDb.subdivisionDao().reviews().isEmpty())
        assertTrue(friendDb.personDao().snapshot().isEmpty())
        assertTrue(friendDb.instructionDao().snapshot().isEmpty())
    }

    @Test
    fun `everything the first officer records stays in their own installation`() = runTest {
        val officerPeople = RoomPersonRepository(officerDb.personDao(), officerDb.syncQueueDao(), officerDb)
        officer.saveProfile("Ambasamudram", "Tirunelveli", "K. Sundaram")
        val station = officer.saveStation(null, "Kalakad", "Station", "Coastal beat")
        officer.saveMatter(null, "Sand mining inquiry", station, "REF/1", "Quarry road")
        val ramesh = officerPeople.createContact("Inspector Ramesh", "Inspector", "Kalakad", "+91-9000000001", "visible")
        officer.saveStaff(ramesh.id, station, "Coastal beat", isStaff = true, active = true)
        officer.saveReview(SubdivisionProjections.SCOPE_ALL, "Monthly review.")

        // The friend's installation is untouched by all of it.
        assertNull(
            "the friend's subdivision must have no name of its own yet",
            friendDb.subdivisionDao().profile("visible"),
        )
        assertTrue(friendDb.subdivisionDao().stations().isEmpty())
        assertTrue(friendDb.subdivisionDao().matters().isEmpty())
        assertTrue(friendDb.subdivisionDao().postings().isEmpty())
        assertTrue(friendDb.subdivisionDao().reviews().isEmpty())
        assertTrue(
            "no contact of the first officer's may appear in the friend's app",
            friendDb.personDao().snapshot().isEmpty(),
        )

        // And nothing about the first officer is discoverable in the friend's data.
        val friendText = buildString {
            friendDb.subdivisionDao().stations().forEach { append(it.name).append(it.notes) }
            friendDb.subdivisionDao().matters().forEach { append(it.title).append(it.description) }
            friendDb.subdivisionDao().profiles().forEach { append(it.name).append(it.officerName) }
            friendDb.personDao().snapshot().forEach { append(it.name).append(it.phone.orEmpty()) }
        }
        listOf("Ambasamudram", "K. Sundaram", "Kalakad", "Ramesh", "+91-9000000001").forEach { secret ->
            assertFalse(
                "the friend's installation must not contain \"$secret\"",
                friendText.contains(secret),
            )
        }
    }

    @Test
    fun `the friend can use the same names without any collision`() = runTest {
        officer.saveProfile("Ambasamudram", "Tirunelveli", "K. Sundaram")
        val officerStation = officer.saveStation(null, "Kalakad", "Station", "")
        friend.saveProfile("Ambasamudram", "Tirunelveli", "Somebody Else")
        val friendStation = friend.saveStation(null, "Kalakad", "Station", "")

        assertEquals("Kalakad", officerDb.subdivisionDao().station(officerStation)?.name)
        assertEquals("Kalakad", friendDb.subdivisionDao().station(friendStation)?.name)
        assertNull(
            "the ids live in different databases and never resolve across them",
            friendDb.subdivisionDao().station(officerStation),
        )
        assertEquals("Somebody Else", friendDb.subdivisionDao().profile("visible")?.officerName)
        assertEquals("K. Sundaram", officerDb.subdivisionDao().profile("visible")?.officerName)
    }
}
