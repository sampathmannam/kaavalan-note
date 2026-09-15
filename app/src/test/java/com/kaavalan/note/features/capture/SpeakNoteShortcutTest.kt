package com.kaavalan.note.features.capture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakNoteShortcutTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
    private val shortcuts = File("src/main/res/xml/shortcuts.xml").readText(Charsets.UTF_8)

    @Test
    fun `normal launcher exposes the long-press speak shortcut`() {
        val mainStart = manifest.indexOf("android:name=\".MainActivity\"")
        assertTrue(mainStart >= 0)
        val mainEnd = manifest.indexOf("</activity>", mainStart)
        val mainActivity = manifest.substring(mainStart, mainEnd)

        assertTrue(mainActivity.contains("android:name=\"android.app.shortcuts\""))
        assertTrue(mainActivity.contains("android:resource=\"@xml/shortcuts\""))
    }

    @Test
    fun `long-press shortcut targets the permission-safe speak ingress`() {
        assertTrue(shortcuts.contains("android:shortcutId=\"speak_note\""))
        assertTrue(shortcuts.contains("android:action=\"${SpeakNoteActivity.ACTION_SPEAK_NOTE}\""))
        assertTrue(shortcuts.contains("android:targetClass=\"com.kaavalan.note.MainActivity\""))
        assertTrue(shortcuts.contains("android:targetPackage=\"@string/shortcut_target_package\""))
    }
}
