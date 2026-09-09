package com.kaavalan.note

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.kaavalan.note.features.capture.NoteBar
import com.kaavalan.note.ui.theme.KaavalanNoteTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NoteBarInteractionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun photoAndVoiceCaptionsDoNotOpenTyping() {
        var typing = 0
        var photo = 0
        var voice = 0
        compose.setContent {
            KaavalanNoteTheme(darkTheme = false) { NoteBar({ typing++ }, { photo++ }, { voice++ }) }
        }
        compose.onNodeWithText("Photo").performClick()
        compose.onNodeWithText("Voice").performClick()
        compose.runOnIdle { assertEquals(0, typing); assertEquals(1, photo); assertEquals(1, voice) }
        compose.onNodeWithTag("capture_open").performClick()
        compose.runOnIdle { assertEquals(1, typing); assertEquals(1, photo); assertEquals(1, voice) }
    }
}
