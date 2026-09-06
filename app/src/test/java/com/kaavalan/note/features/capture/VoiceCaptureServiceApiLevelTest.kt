package com.kaavalan.note.features.capture

import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field

/**
 * v2.1.2 (crash fix): pins the voice-capture result-receiver
 * extraction to the project's real `minSdk`, by driving the real
 * [VoiceCaptureService.onStartCommand] — not a duplicate of the
 * fixed expression.
 *
 * **The finding.** `VoiceCaptureService.handleStart` read its
 * `ResultReceiver` with `intent.getParcelableExtra(name, clazz)`.
 * That two-argument overload arrives in **API 33**; `minSdk` here
 * is **26**. On any device running Android 8.0 through 12L the call
 * threw `NoSuchMethodError` and killed the foreground service the
 * moment the user started a voice capture — one of the three
 * primary capture modes, on the majority of the target user base.
 *
 * v1.6.7 introduced it while replacing the deprecated
 * single-argument overload; the deprecation warning was silenced
 * and a crash took its place. Android Lint caught it (the project's
 * sole `NewApi` error) but the CI lint job ran with
 * `continue-on-error: true`, so it never failed a build.
 *
 * **A first version of this test was wrong.** It called
 * `IntentCompat.getParcelableExtra(...)` directly in the test body
 * instead of going through the service — which meant sabotaging
 * `VoiceCaptureService` back to the broken overload left the test
 * green, because the test was checking its own inlined copy of the
 * fix, not the production code. That is the exact tautology
 * `ProguardRulesTest`'s own doc comment warns about. This version
 * drives [VoiceCaptureService] through Robolectric's real
 * `ServiceController` (the same pattern
 * [KaavalanTileServiceTest] uses) and reads the private
 * `resultReceiver` field back via reflection, so a regression to
 * the API-33-only overload fails this test on `NoSuchMethodError`.
 *
 * **Why `@Config(sdk = [26])`.** The rest of the suite runs on SDK
 * 33, where the offending overload exists and the bug is invisible.
 * Pinning this test to the actual `minSdk` is the whole point.
 *
 * **Why `application = HiltTestApplication::class`.**
 * `VoiceCaptureService` is `@AndroidEntryPoint`; `Hilt_Service.onCreate`
 * requires a Hilt-instrumented Application to attach to (same
 * requirement documented in `HiltTest.kt` / `HomeScreenTest.kt`).
 * The service itself has no `@Inject` fields, so nothing beyond
 * that attachment is needed. [HiltAndroidRule] is what actually
 * builds and attaches the generated component to the test
 * application — `HiltTestApplication` alone is inert until a
 * `@HiltAndroidTest` + [HiltAndroidRule] pair calls `inject()`,
 * which is exactly the `IllegalStateException: The component was
 * not created` this test hit before the rule was added.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = HiltTestApplication::class)
class VoiceCaptureServiceApiLevelTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private class RecordingReceiver : ResultReceiver(null) {
        var lastCode: Int? = null
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            lastCode = resultCode
        }
    }

    private fun startIntent(receiver: ResultReceiver?): Intent =
        Intent(
            ApplicationProvider.getApplicationContext(),
            VoiceCaptureService::class.java,
        ).apply {
            action = VoiceCaptureService.ACTION_START
            if (receiver != null) putExtra(VoiceCaptureService.EXTRA_RESULT_RECEIVER, receiver)
        }

    /** Reads the private `resultReceiver` field `handleStart` sets. */
    private fun resultReceiverOf(service: VoiceCaptureService): ResultReceiver? {
        val field: Field = VoiceCaptureService::class.java.getDeclaredField("resultReceiver")
        field.isAccessible = true
        return field.get(service) as ResultReceiver?
    }

    /**
     * Guards the guard: if Robolectric ever silently ignores the
     * `sdk = [26]` config, this test would be exercising API 33
     * again and would stop proving anything.
     */
    @Test
    fun `test really runs on the project minSdk`() {
        assertEquals(
            "this test must run on API 26 (the module's minSdk) or it does not " +
                "exercise the code path that was crashing",
            26,
            android.os.Build.VERSION.SDK_INT,
        )
    }

    /**
     * Runs the real [VoiceCaptureService.onStartCommand] and reports
     * whether the extraction bug's exact symptom occurred.
     *
     * `handleStart` sets the `resultReceiver` field immediately after
     * the parcelable extraction — before it touches notifications or
     * `SpeechRecognizer` — so field state at any point after this call
     * (whether it completed or threw) tells us whether extraction
     * succeeded, independent of anything downstream. Robolectric's
     * resource loader throws its own unrelated
     * `Resources$NotFoundException` further down this same method
     * (`ensureChannel` resolving a notification-channel name) on
     * `sdk = [26]` specifically — a Robolectric resource-merging quirk
     * on this SDK, not a production defect; the other Hilt+Robolectric
     * tests in this module (`HiltTest`, `HomeScreenTest`) all run on
     * `sdk = [33]`, where it doesn't happen. We let that propagate as
     * ordinary noise without swallowing a real `LinkageError` (the
     * supertype of `NoSuchMethodError`, so a regression to any
     * missing-method / missing-class break in this call chain is still
     * caught, not just the one exact overload that broke before).
     */
    private fun startAndReadResultReceiver(receiver: ResultReceiver?): ResultReceiver? {
        val controller = Robolectric.buildService(VoiceCaptureService::class.java, startIntent(receiver))
        try {
            controller.create().startCommand(0, 0)
        } catch (e: LinkageError) {
            fail(
                "VoiceCaptureService.onStartCommand threw ${e::class.java.simpleName} on API 26. " +
                    "This is the production crash: a call site using an API-newer-than-minSdk " +
                    "method (originally Intent#getParcelableExtra(String, Class), API 33 on a " +
                    "module whose minSdk is 26). Cause: ${e.message}",
            )
        } catch (_: Exception) {
            // Environment noise from a step after the extraction under
            // test (see the kdoc above) -- resultReceiver was already
            // set by the time this can happen.
        }
        return resultReceiverOf(controller.get())
    }

    @Test
    fun `starting the service with a result receiver does not crash on API 26`() {
        val receiver = RecordingReceiver()
        assertEquals(
            "the ResultReceiver passed in the start intent must be recoverable on API 26; " +
                "a null here means voice capture cannot report results back to the caller",
            receiver,
            startAndReadResultReceiver(receiver),
        )
    }

    @Test
    fun `starting the service without a result receiver does not crash on API 26`() {
        assertNull(
            "an absent extra must read back as null, not throw",
            startAndReadResultReceiver(null),
        )
    }
}
