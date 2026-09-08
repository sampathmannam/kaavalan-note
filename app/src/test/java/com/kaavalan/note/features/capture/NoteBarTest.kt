package com.kaavalan.note.features.capture
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Structural counterpart to the device-level NoteBarInteractionsTest. */
class NoteBarTest {
    private val source = File("src/main/java/com/kaavalan/note/features/capture/NoteBar.kt").readText()

    @Test fun shortcutsHaveWholeCaptionTargets_andDisjointCallbacks() {
        assertTrue(source.contains("CaptureShortcut(Icons.Outlined.PhotoCamera, stringResource(R.string.note_bar_camera), onCameraClick)"))
        assertTrue(source.contains("CaptureShortcut(Icons.Outlined.Mic, stringResource(R.string.note_bar_mic), onMicClick)"))
        val shortcut = source.substringAfter("private fun CaptureShortcut")
        assertTrue(shortcut.contains(".clickable(role = Role.Button, onClick = onClick)"))
        assertTrue(shortcut.contains("heightIn(min = 56.dp)"))
        assertTrue(shortcut.contains("Text(label"))
    }

    @Test fun typingDoesNotOwnAnOuterSurfaceWrappingTheOtherActions() {
        assertTrue(source.contains("FilledTonalButton(onClick = onTextClick"))
        assertFalse(source.contains("Surface(onClick = onTextClick"))
    }
}
