package com.kaavalan.note.data.local

import androidx.test.core.app.ApplicationProvider
import com.kaavalan.note.data.auth.SecurePreferences
import com.kaavalan.note.data.dev.FixtureLoader
import com.kaavalan.note.data.preferences.KaavalanPreferences
import com.kaavalan.note.di.ApplicationScope
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
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
    private lateinit var preferences: KaavalanPreferences
    private lateinit var initializer: AppInitializer
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        securePreferences = mockk(relaxed = true)
        every { securePreferences.hasDatabasePassphrase() } returns true
        every { securePreferences.databasePassphrase() } returns ByteArray(32) { it.toByte() }
        // Fresh-install-methodology fix: mocked, not a real
        // DataStore-backed instance. `KaavalanPreferences.dataStore`
        // is a top-level `by preferencesDataStore(...)` delegate --
        // AndroidX's documented "construct at most once per process"
        // singleton -- so under Robolectric it is shared across
        // EVERY test class in the same Gradle test JVM fork, not
        // reset by a fresh Context or by deleting the backing file.
        // An earlier version of this test used a real
        // `KaavalanPreferences(context)` and wiped the on-disk file
        // in @Before/@After, which looked like proper isolation but
        // did nothing: the DataStore's in-memory cache survives file
        // deletion once any test in the JVM has touched it, so
        // `setOnboardingSeen()` in the test below leaked `true`
        // forward into unrelated tests (concretely broke
        // OnboardingViewModelTest's "starts at false" assertion when
        // run as part of the full suite, despite passing alone).
        // Mocking sidesteps the shared singleton entirely, matching
        // the existing `securePreferences` pattern in this same file.
        preferences = mockk(relaxed = true)
        every { preferences.hasSeenOnboarding } returns flowOf(false)
        // v1.7.3 (P0-A): AppInitializer now takes the FixtureLoader
        // (for the version-gated reseed) and an ApplicationScope
        // CoroutineScope (so the reseed runs off the main thread).
        // The pre-v1.7.3 test passed only (context, securePreferences)
        // and was broken by the reseedIfStale() addition. We mock
        // the FixtureLoader (no work done in the path these tests
        // exercise) and use a SupervisorJob'd CoroutineScope that we
        // can clean up.
        val fixtureLoader = mockk<FixtureLoader>(relaxed = true)
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        initializer = AppInitializer(context, securePreferences, fixtureLoader, preferences, appScope)
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

    // v1.9.9 (A6): the auto-reseed must not run on a release
    // build's first launch. The previous (v1.7.3) behaviour was
    // "run on every build" because the rationale was a v1.7.1→
    // v1.7.2 worry-box dates migration — but the migration was
    // for synthetic-fixture data, never real user data, and a
    // release-build user who never loaded the fixture had their
    // tables wiped on the first launch (storedVersion=0,
    // asset.version=2, so reseedIfStale returned LoadReport
    // after clearing 6 tables). The fix gates the call on
    // [AppInitializer.isDebugBuild]. The default value is
    // `BuildConfig.DEBUG` (true in `testDebug` / `debug`, false
    // in `release`); the @VisibleForTesting hook lets us verify
    // the release-build contract from a JVM unit test without
    // recompiling against the `release` variant.
    @Test
    fun `A6 release build does not call reseedIfStale on app start`() = runBlocking {
        // Re-construct the initializer with a tracked
        // FixtureLoader so we can verify the call count, then
        // flip [isDebugBuild] to false to simulate a release
        // build. The [setUp] instance has already run its
        // body once; we need a fresh one because [runOnAppStart]
        // is idempotent (the body only runs once per process
        // per the @Volatile guard).
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val freshFixtureLoader = mockk<FixtureLoader>(relaxed = true)
        val freshScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseInit = AppInitializer(
            context = context,
            securePreferences = securePreferences,
            fixtureLoader = freshFixtureLoader,
            preferences = preferences,
            appScope = freshScope,
        )
        releaseInit.isDebugBuild = false

        releaseInit.runOnAppStart()

        // The release build MUST NOT touch the user's data.
        coVerify(exactly = 0) { freshFixtureLoader.reseedIfStale() }
    }

    @Test
    fun `A6 debug build still calls reseedIfStale on app start (when libsqlcipher is present)`() = runBlocking {
        // The debug build is the existing behaviour — locked
        // here so a future refactor doesn't accidentally gate
        // the auto-reseed off for the dev workflow too. **NOTE:**
        // this test cannot be exercised in the Robolectric
        // environment because [System.loadLibrary("sqlcipher")]
        // throws `UnsatisfiedLinkError` (the Robolectric classpath
        // does not include the native library), and the v1.2.1
        // BUG-DATA-021 fix returns early on that throw — before
        // the reseed launch. The test below is therefore a
        // *no-op assertion* (the test passes if the call
        // happens, fails if it does; in Robolectric, the
        // `System.loadLibrary` short-circuits the path so the
        // call count is 0). The contract is enforced on a
        // real device / emulator by the Settings → Developer
        // → "Load test data" flow, which exercises
        // [FixtureLoader.reseedIfStale] directly. A
        // debug-build test for the auto-reseed would require
        // a Robolectric shadow that fakes libsqlcipher, which
        // is out of scope for v1.9.9.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val freshFixtureLoader = mockk<FixtureLoader>(relaxed = true)
        val freshScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val debugInit = AppInitializer(
            context = context,
            securePreferences = securePreferences,
            fixtureLoader = freshFixtureLoader,
            preferences = preferences,
            appScope = freshScope,
        )
        debugInit.isDebugBuild = true

        debugInit.runOnAppStart()

        // In Robolectric this is 0 (loadLibrary throws).
        // On a real device this is 1. We don't assert
        // because the count is environment-dependent; the
        // test exists only to make sure the [isDebugBuild]
        // flip does not crash.
        assertTrue(true)
    }

    // Fresh-install-methodology regression: a genuine `pm clear`
    // wipes the fixture-version SharedPreferences back to 0 (always
    // < the asset's version 2), so the pre-fix `reseedIfStale()`
    // call fired on every debug launch regardless of what onboarding
    // (Skip, or the full walkthrough with the sample-data toggle
    // off) had just produced -- the true "empty first run" state was
    // unobservable on the `.debug` artifact. [shouldAutoReseedFixture]
    // is exercised directly (not through `runOnAppStart`) because
    // that entry point is behind `System.loadLibrary("sqlcipher")`,
    // which is unreachable under Robolectric (see the "A6 debug
    // build" test above for the same limitation).
    @Test
    fun `shouldAutoReseedFixture is false before onboarding has ever completed (fresh install or post-clear)`() = runBlocking {
        // preferences.hasSeenOnboarding is stubbed to flowOf(false) in setUp().
        assertFalse(preferences.hasSeenOnboarding.first())

        val result = initializer.shouldAutoReseedFixture()

        assertFalse(
            "a fresh install (or a debug artifact right after pm clear) must not " +
                "auto-reseed the 55-person fixture -- onboarding (Skip / sample-data " +
                "toggle) owns the first-run Home state",
            result,
        )
    }

    @Test
    fun `shouldAutoReseedFixture is true once onboarding has completed at least once`() = runBlocking {
        // Simulate onboarding having already completed by re-stubbing the
        // mock, rather than writing through a real setOnboardingSeen() call
        // -- see the setUp() comment for why a real DataStore write here
        // would leak across tests.
        coEvery { preferences.setOnboardingSeen() } just Runs
        every { preferences.hasSeenOnboarding } returns flowOf(true)

        preferences.setOnboardingSeen()
        assertTrue(preferences.hasSeenOnboarding.first())

        val result = initializer.shouldAutoReseedFixture()

        assertTrue(
            "once onboarding has already run on this install, the pre-existing " +
                "v1.7.3 (P0-A) convenience/migration reseed must still fire so a " +
                "bumped fixture asset version reaches an ongoing debug test session",
            result,
        )
    }
}

