package com.kaavalan.note

import android.content.Intent
import android.os.Build
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Physical-OEM contract: the installed speech service is visible to Kaavalan note. */
@RunWith(AndroidJUnit4::class)
class VoiceRecognitionAvailabilityTest {

    @Test
    fun physicalDeviceHasAVisibleSpeechRecognitionService() {
        assumeFalse(
            "AOSP emulators do not consistently bundle a speech recognition provider",
            Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(SpeechRecognizer.isRecognitionAvailable(context))
        val services = context.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            0,
        )
        assertTrue("SpeechRecognizer was available but its service was not package-visible", services.isNotEmpty())
    }
}
