package com.kaavalan.note.data.local

import androidx.test.core.app.ApplicationProvider
import com.kaavalan.note.data.auth.SecurePreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v1.2.1 regression test (BUG-DATA-021 / BUG-DATA-022 / BUG-DATA-023 / BUG-DATA-024).
 *
 * Locks the idempotency contract of [AppInitializer]:
 *  - `runOnSignOut` is safe to call multiple times. The body runs
 *    at most once per process; subsequent calls are no-ops until
 *    `resetSignOutGuard()` is called.
 *  - `runOnAppStart` does not re-throw UnsatisfiedLinkError. The
 *    audit found that the v1.1 path crashed the app before the
 *    first frame if the lib was missing. v1.2.1 logs + returns.
 *
 * (We don't test the runOnAppStart idempotency count here because
 * Robolectric's classpath doesn't have libsqlcipher.so — the
 * `System.loadLibrary("sqlcipher")` call throws UnsatisfiedLinkError
 * in the test. The post-fix code handles that path gracefully
 * (log + return), but then the "passphrase pre-warm read" doesn't
 * run, so the verify-count assertion would be 0. We instead test
 * the contract "doesn't crash + the guard is exposed" elsewhere.)
 *
 * The tests use Robolectric for the Android Context. SecurePreferences
 * is mocked (no Android Keystore dependency).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppInitializerTest {

    private lateinit var securePreferences: SecurePreferences
    private lateinit var initializer: AppInitializer
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        securePreferences = mockk(relaxed = true)
        every { securePreferences.hasDatabasePassphrase() } returns true
        every { securePreferences.databasePassphrase() } returns ByteArray(32) { it.toByte() }
        initializer = AppInitializer(context, securePreferences)
        dbFile = context.getDatabasePath(AppDatabase.NAME)
    }

    @Test
    fun `BUG-DATA-021 runOnAppStart does not crash on missing libsqlcipher (Robolectric case)`() {
        // Robolectric's classpath doesn't have libsqlcipher.so; the
        // loadLibrary call throws UnsatisfiedLinkError. v1.2.1 must
        // catch + log + return. The pre-v1.2.1 path re-threw and
        // crashed the app before the first frame.
        initializer.runOnAppStart()  // must not throw
        // The post-condition is just "the process is alive".
        assertTrue(true)
    }

    @Test
    fun `BUG-DATA-022 runOnSignOut is idempotent across multiple calls`() {
        // The DB file may or may not exist. We don't care — the
        // idempotency is about the guard, not the delete.
        initializer.runOnSignOut()
        // Second call: should be a no-op. The passphrase is already
        // cleared, so clearDatabasePassphrase() should not be called
        // a second time.
        initializer.runOnSignOut()
        verify(exactly = 1) { securePreferences.clearDatabasePassphrase() }
    }

    @Test
    fun `BUG-DATA-024 resetSignOutGuard allows a second sign-out to run`() {
        initializer.runOnSignOut()
        initializer.runOnSignOut()  // no-op
        initializer.resetSignOutGuard()
        initializer.runOnSignOut()  // body runs again
        verify(exactly = 2) { securePreferences.clearDatabasePassphrase() }
    }

    @Test
    fun `runOnAppStart does not crash on missing M2 plain DB`() {
        // Default: dbFile may or may not exist; runOnAppStart should
        // never throw.
        initializer.runOnAppStart()  // must not throw
        assertTrue(true)
    }

    @Test
    fun `runOnSignOut does not crash when dbFile does not exist`() {
        if (dbFile.exists()) dbFile.delete()
        initializer.runOnSignOut()  // must not throw
        assertTrue(true)
    }

}
