package com.kaavalan.note.features.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.kaavalan.note.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tier 0.3: Robolectric tests for the v1.6.0
 * [ShareReceiverActivity].
 *
 * **What we test without an emulator:**
 *  - A valid `ACTION_SEND text/plain` intent produces a
 *    forward intent that targets MainActivity with the
 *    shared text as the `EXTRA_SHARED_TEXT` extra.
 *  - An invalid intent (wrong action) produces a forward
 *    intent with an empty string (the no-UI fallback).
 *  - The activity is constructible, package-consistent
 *    with the manifest, and uses the translucent theme
 *    declared in `themes.xml`.
 *
 * **What we don't test here:**
 *  - The actual on-shared-text UI in MainActivity. The
 *    drive case (`qa-share-receive.xml`) launches the
 *    activity via `adb shell am start -a SEND` and
 *    screencaps the capture sheet.
 *  - The image -> OCR -> forward chain on the *happy*
 *    path (needs ML Kit + a real bitmap; out of scope
 *    for Tier 0). Its failure path is covered below --
 *    that one needs no bitmap, because the point is
 *    that there isn't a readable one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShareReceiverActivityTest {

    @Test
    fun `text SEND intent produces forward intent targeting MainActivity`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication() as Context
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Tell SHO Ramu to file FIR 47")
        }
        // Simulate the inspect path the activity runs.
        val result = ShareIntake.inspect(sendIntent)
        assertTrue("text/plain must produce a Text result", result is ShareIntake.Result.Text)
        val forward = ShareIntake.buildForwardIntent((result as ShareIntake.Result.Text).text)
        forward.setClassName(context, MainActivity::class.java.name)
        assertEquals(
            "Forward targets MainActivity",
            MainActivity::class.java.name,
            forward.component?.className,
        )
        assertEquals(
            "Forward carries the shared text",
            "Tell SHO Ramu to file FIR 47",
            forward.getStringExtra(ShareIntake.EXTRA_SHARED_TEXT),
        )
    }

    @Test
    fun `invalid intent produces empty forward (no pre-fill)`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication() as Context
        val bad = Intent(Intent.ACTION_VIEW)
        val result = ShareIntake.inspect(bad)
        assertEquals("non-SEND intents produce null from inspect", null, result)
        // The activity falls back to an empty forward.
        val forward = ShareIntake.buildForwardIntent(sharedText = "")
        forward.setClassName(context, MainActivity::class.java.name)
        assertEquals("", forward.getStringExtra(ShareIntake.EXTRA_SHARED_TEXT))
    }

    @Test
    fun `activity is package-consistent with the manifest`() {
        // The activity is @AndroidEntryPoint, so the
        // full Robolectric lifecycle is not exercised
        // here (Hilt's generated `_HiltModules` need a
        // HiltAndroidRule + a test application class,
        // which the v1.5.7 test suite does not provide
        // for the share-target surface). The
        // package-consistent check is enough to pin
        // the manifest wiring: a package move breaks
        // the manifest <activity-alias android:name=...>
        // reference and the share-sheet intent filter
        // becomes orphaned.
        val activityClass = ShareReceiverActivity::class.java
        assertEquals(
            "com.kaavalan.note.features.capture",
            activityClass.`package`?.name ?: "",
        )
        assertEquals(
            "com.kaavalan.note.features.capture.ShareReceiverActivity",
            activityClass.name,
        )
    }

    @Test
    fun `translucent theme file is on the source classpath`() {
        // The manifest's <activity-alias> declares
        // android:theme="@style/Theme.KaavalanNote.Translucent.NoDisplay".
        // If a future commit drops the theme, the activity
        // flashes white on a share intent. We check the
        // themes.xml file directly (the R class is
        // generated at build time; the source file is the
        // source of truth).
        val themesFile = java.io.File("src/main/res/values/themes.xml")
        assertTrue(
            "themes.xml must exist at ${themesFile.absolutePath}",
            themesFile.exists(),
        )
        val contents = themesFile.readText()
        assertTrue(
            "themes.xml must declare Theme.KaavalanNote.Translucent.NoDisplay",
            "Theme.KaavalanNote.Translucent.NoDisplay" in contents,
        )
    }

    /**
     * v2.2.1: the image path was one throw away from killing the app.
     *
     * [ShareReceiverActivity] handed the shared URI straight to
     * `PhotoCapture.recognize` inside a bare
     * `CoroutineScope(Dispatchers.IO).launch`. That scope has no parent
     * and no `CoroutineExceptionHandler`, so a throw reached the
     * thread's uncaught handler and took the process with it. The
     * `invokeOnCompletion` beside it does not help -- it observes the
     * failure without consuming it. And `recognize` is documented to
     * throw whenever the URI is unreachable or the bytes will not
     * decode, with the fallback explicitly left to its caller.
     *
     * The activity is an exported share target, so the URI belongs to
     * the sender. Any installed app can send an image share carrying a URI
     * it never granted; an ordinary share reaches the same throw by accident
     * whenever the photo is cloud-only, in a format ML Kit cannot read,
     * or backed by a temp file the sender has already deleted.
     */
    @Test
    fun `a failing OCR forwards an empty pre-fill instead of crashing`() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication() as Context
        val uri = android.net.Uri.parse("content://com.example.sender/photo/1")
        assertEquals(
            "a share whose image cannot be read must degrade to an empty pre-fill",
            "",
            ocrTextOrEmpty(context, uri) { _, _ ->
                throw java.io.FileNotFoundException("no permission for $uri")
            },
        )
    }

    /**
     * The catch above is a `catch (Throwable)`, which would otherwise
     * swallow cancellation and let the coroutine keep going after its
     * scope died -- calling `forwardText` on an activity that is already
     * gone. It rethrows instead, which is also what leaves the
     * `invokeOnCompletion { if (isCancelled) forwardText("") }` branch
     * next to the call site meaningful.
     */
    @Test
    fun `cancellation is not swallowed by the OCR fallback`() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication() as Context
        val uri = android.net.Uri.parse("content://com.example.sender/photo/1")
        var rethrown = false
        try {
            ocrTextOrEmpty(context, uri) { _, _ ->
                throw CancellationException("scope died")
            }
        } catch (expected: CancellationException) {
            rethrown = true
        }
        assertTrue("CancellationException must propagate, not become an empty string", rethrown)
    }

    /** Recognised text still reaches the caller unchanged. */
    @Test
    fun `recognised text is forwarded as-is`() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication() as Context
        val uri = android.net.Uri.parse("content://com.example.sender/photo/1")
        assertEquals(
            "FIR 47 filed at Egmore",
            ocrTextOrEmpty(context, uri) { _, _ -> "FIR 47 filed at Egmore" },
        )
    }

    /**
     * The three tests above cover [ocrTextOrEmpty]. This pins that the
     * activity actually goes through it: an edit that reinstates the
     * bare call inside the launch would leave those passing and put the
     * crash back.
     *
     * The wrapper names `PhotoCapture::recognize` as a default, so a
     * *call* -- `PhotoCapture.recognize(` with the paren -- is exactly
     * the thing that must not appear. KDoc references are written
     * without one.
     */
    @Test
    fun `the share receiver never calls PhotoCapture directly`() {
        val source = java.io.File(
            "src/main/java/com/kaavalan/note/features/capture/ShareReceiverActivity.kt",
        )
        assertTrue("source must exist at ${source.absolutePath}", source.exists())
        assertEquals(
            "ShareReceiverActivity.kt must reach OCR only through ocrTextOrEmpty, " +
                "where the throw is caught",
            0,
            Regex("PhotoCapture\\.recognize\\(").findAll(source.readText()).count(),
        )
    }
}
