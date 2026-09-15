package com.kaavalan.note.features.capture

import android.content.Intent
import com.kaavalan.note.MainActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SpeakNoteActivityTest {

    @Test
    fun `launcher entry forwards a speak-note request to MainActivity and finishes`() {
        val activity = Robolectric.buildActivity(SpeakNoteActivity::class.java).create().get()
        val forwarded = shadowOf(activity).nextStartedActivity

        assertEquals(MainActivity::class.java.name, forwarded.component?.className)
        assertEquals(SpeakNoteActivity.ACTION_SPEAK_NOTE, forwarded.action)
        assertTrue(forwarded.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(forwarded.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `speak-note action is distinct from ordinary quick capture`() {
        assertEquals("com.kaavalan.note.action.SPEAK_NOTE", SpeakNoteActivity.ACTION_SPEAK_NOTE)
        assertFalse(
            SpeakNoteActivity.ACTION_SPEAK_NOTE == KaavalanCaptureWidget.ACTION_QUICK_CAPTURE,
        )
    }

    @Test
    fun `speak-note entry is exported and has its dedicated launcher label`() {
        val manifest = File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        val entryStart = manifest.indexOf("android:name=\".features.capture.SpeakNoteActivity\"")
        assertTrue(entryStart >= 0)
        val entryEnd = manifest.indexOf("</activity>", entryStart)
        val entry = manifest.substring(entryStart, entryEnd)

        assertTrue(entry.contains("android:exported=\"true\""))
        assertTrue(entry.contains("android:label=\"@string/speak_note_launcher_label\""))
        assertTrue(entry.contains("android.intent.action.MAIN"))
        assertTrue(entry.contains("android.intent.category.LAUNCHER"))
    }
}
