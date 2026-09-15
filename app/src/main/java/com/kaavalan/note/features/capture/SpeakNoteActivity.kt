package com.kaavalan.note.features.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.kaavalan.note.MainActivity

/**
 * Launcher-visible entry point for hardware gestures that can open an app but cannot send a
 * custom intent, including Motorola Quick Launch's double-tap-on-the-back gesture.
 *
 * This activity never touches the microphone itself. It immediately brings [MainActivity] to
 * the foreground with [ACTION_SPEAK_NOTE], then finishes. Starting capture from the visible app
 * keeps Android's while-in-use microphone permission and foreground-service rules intact.
 */
class SpeakNoteActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(mainActivityIntent(this))
        finish()
    }

    companion object {
        const val ACTION_SPEAK_NOTE: String = "com.kaavalan.note.action.SPEAK_NOTE"

        internal fun mainActivityIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_SPEAK_NOTE
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
    }
}
