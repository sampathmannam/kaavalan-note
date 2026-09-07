package com.kaavalan.note.features.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.2.1: the two halves of the share target, checked against each
 * other rather than each against its own assumptions.
 *
 * Sharing a photo into Kaavalan note did nothing at all. Not a crash,
 * not a message -- the app opened on the last screen and the image was
 * gone. The OCR that exists to read a photographed instruction never
 * ran.
 *
 * The cause is a name. The manifest's share target is an
 * `<activity-alias>` called `.features.capture.ShareReceiverActivity`,
 * but it declares `android:targetActivity=".MainActivity"`. An alias
 * name is just a component name; it does not have to correspond to a
 * class, and this one does not. `ShareReceiverActivity` the Kotlin
 * class is never declared as an `<activity>`, so it is never
 * instantiated -- the share sheet launches [com.kaavalan.note.MainActivity]
 * directly with the original SEND intent.
 *
 * MainActivity's handler was written against the other reading. Its
 * `ShareIntake.Result.Image` branch was empty, with the comment
 * "Receiver activity already OCR'd; main entry is text." No receiver
 * activity had OCR'd anything.
 *
 * Every existing test passed throughout. `ShareIntakeTest` drives
 * `inspect()` with real SEND intents and gets `Result.Image` back;
 * `ShareReceiverActivityTest` drives the receiver's forward intent.
 * Both halves are correct in isolation. Nothing asserted that the
 * component the manifest actually launches handles what the manifest
 * actually advertises, which is the only thing that was wrong.
 *
 * So that is what this pins.
 */
class ShareTargetWiringTest {

    private val manifest: String =
        File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)

    private val mainActivity: String =
        File("src/main/java/com/kaavalan/note/MainActivity.kt").readText(Charsets.UTF_8)

    @Test
    fun `the share sheet launches MainActivity, not the receiver class`() {
        assertTrue(
            "the share target must remain an <activity-alias> targeting MainActivity; " +
                "if this changes, MainActivity's Image branch below may no longer be " +
                "the component that receives the share",
            manifest.contains("android:targetActivity=\".MainActivity\""),
        )
        assertTrue(
            "ShareReceiverActivity appears in the manifest exactly once, as the alias " +
                "name. A second occurrence would mean it is also declared as a real " +
                "<activity>, which changes where a share lands -- re-read this test and " +
                "MainActivity.consumeSharedText together if that ever happens",
            manifest.split("ShareReceiverActivity").size - 1 == 1,
        )
    }

    @Test
    fun `MainActivity OCRs a shared image instead of dropping it`() {
        assertTrue(
            "the share alias advertises image/*, so the component it targets must do " +
                "something with an image payload",
            manifest.contains("android:mimeType=\"image/*\""),
        )
        assertTrue(
            "MainActivity must OCR a shared image; its Result.Image branch was an " +
                "empty block, which silently discarded every shared photo",
            mainActivity.contains("ocrTextOrEmpty("),
        )
        assertFalse(
            "the claim that a receiver activity already OCR'd the image was what kept " +
                "that branch empty; no receiver activity runs",
            mainActivity.contains("Receiver activity already OCR"),
        )
    }

    @Test
    fun `the shared-image path goes through the guarded OCR call`() {
        assertFalse(
            "the shared URI belongs to the sending app and PhotoCapture.recognize " +
                "throws on a revoked, cloud-only or undecodable one. MainActivity must " +
                "reach OCR through ocrTextOrEmpty, which degrades to an empty pre-fill",
            mainActivity.contains("PhotoCapture.recognize("),
        )
    }
}
