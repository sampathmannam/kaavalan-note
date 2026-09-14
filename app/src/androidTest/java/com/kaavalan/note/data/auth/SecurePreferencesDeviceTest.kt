package com.kaavalan.note.data.auth

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Executes the AndroidKeyStore-backed [SecurePreferences] contract on an
 * actual Android runtime. These checks intentionally live in androidTest:
 * Robolectric cannot provide the AndroidKeyStore used by [SecurePreferences].
 *
 * A test-only factory redirects the encrypted preferences file to an isolated
 * name, so clearing it cannot invalidate the target app's SQLCipher database.
 */
@RunWith(AndroidJUnit4::class)
class SecurePreferencesDeviceTest {

    private lateinit var baseContext: Context

    @Before
    fun setUp() {
        baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        baseContext.deleteSharedPreferences(ISOLATED_FILE_NAME)
    }

    @After
    fun tearDown() {
        runCatching { newPrefs().clearDatabasePassphrase() }
        baseContext.deleteSharedPreferences(ISOLATED_FILE_NAME)
    }

    private fun newPrefs(): SecurePreferences =
        SecurePreferences.forTesting(baseContext, ISOLATED_FILE_NAME)

    @Test
    fun databasePassphraseReturns32BytesOnFirstCall() {
        assertEquals(32, newPrefs().databasePassphrase().size)
    }

    @Test
    fun databasePassphraseIsStableAcrossCalls() {
        val prefs = newPrefs()
        assertEquals(prefs.databasePassphrase().toList(), prefs.databasePassphrase().toList())
    }

    @Test
    fun hasDatabasePassphraseTracksPresence() {
        val prefs = newPrefs()
        assertFalse(prefs.hasDatabasePassphrase())
        prefs.databasePassphrase()
        assertTrue(prefs.hasDatabasePassphrase())
    }

    @Test
    fun clearDatabasePassphraseRemovesStoredKey() {
        val prefs = newPrefs()
        prefs.databasePassphrase()
        assertTrue(prefs.hasDatabasePassphrase())
        assertTrue(prefs.clearDatabasePassphrase())
        assertFalse(prefs.hasDatabasePassphrase())
    }

    @Test
    fun clearDatabasePassphraseReturnsFalseWhenEmpty() {
        assertFalse(newPrefs().clearDatabasePassphrase())
    }

    @Test
    fun independentInstancesReadTheSameKey() {
        val first = newPrefs().databasePassphrase()
        val second = newPrefs().databasePassphrase()
        assertEquals(first.toList(), second.toList())
    }

    private companion object {
        const val ISOLATED_FILE_NAME = "kaavalan_note_secure_prefs_device_test"
    }
}
