package com.kaavalan.note.features.capture
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Structural counterpart to the device-level NoteBarInteractionsTest. */
class NoteBarTest {
    private val source = File("src/main/java/com/kaavalan/note/features/capture/NoteBar.kt").readText()

    @Test fun shortcutsHaveWholeCaptionTargets_andDisjointCallbacks() {
        assertTrue(source.contains("CaptureShortcut(Icons.Outlined.PhotoCamera, stringResource(R.string.note_bar_camera), onCameraClick)"))
        assertTrue(source.contains("SpeakNoteButton(stringResource(R.string.note_bar_mic), onMicClick)"))
        val shortcut = source.substringAfter("private fun CaptureShortcut")
        assertTrue(shortcut.contains(".clickable(role = Role.Button, onClick = onClick)"))
        assertTrue(shortcut.contains("heightIn(min = 56.dp)"))
        assertTrue(shortcut.contains("Text(label"))
    }

    @Test fun voiceIsAProminentLabelledMaterialButton() {
        val voice = source.substringAfter("private fun SpeakNoteButton").substringBefore("private fun CaptureShortcut")
        assertTrue(voice.contains("FilledTonalButton("))
        assertTrue(voice.contains("widthIn(min = 96.dp)"))
        assertTrue(voice.contains("heightIn(min = 56.dp)"))
        assertTrue(voice.contains("testTag(\"capture_voice\")"))
        assertTrue(voice.contains("Text(label"))
    }

    @Test fun typingDoesNotOwnAnOuterSurfaceWrappingTheOtherActions() {
        assertTrue(source.contains("Button(onClick = onTextClick"))
        assertFalse(source.contains("Surface(onClick = onTextClick"))
    }
}
