package com.kaavalan.note.features.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest's share target is an activity alias that targets
 * MainActivity. Keep image intake wired there: treating the alias name as a
 * real receiver silently dropped every shared photo.
 */
class ShareTargetWiringTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
    private val mainActivity = File("src/main/java/com/kaavalan/note/MainActivity.kt")
        .readText(Charsets.UTF_8)

    @Test
    fun shareAliasTargetsMainActivity() {
        assertTrue(
            "the share alias must target MainActivity, where shared intents are consumed",
            manifest.contains("android:targetActivity=\".MainActivity\""),
        )
        assertTrue(
            "the share target must accept shared images as well as text",
            manifest.contains("android:mimeType=\"image/*\""),
        )
    }

    @Test
    fun mainActivityUsesGuardedOcrForSharedImages() {
        assertTrue(
            "shared images must be OCR'd through the guarded fallback",
            mainActivity.contains("ocrTextOrEmpty("),
        )
        assertFalse(
            "shared image intake must not call throwing PhotoCapture.recognize directly",
            mainActivity.contains("PhotoCapture.recognize("),
        )
        assertFalse(
            "MainActivity must not assume a separate receiver processed the image",
            mainActivity.contains("Receiver activity already OCR"),
        )
    }
}
