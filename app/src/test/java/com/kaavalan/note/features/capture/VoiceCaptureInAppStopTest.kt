package com.kaavalan.note.features.capture

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tier 0.4: tests for the in-app voice stop button.
 *
 * The user-visible behaviour is:
 *  1. User taps the mic icon in the NoteBar.
 *  2. [VoiceCaptureService.handleStart] fires; it sets
 *     [VoiceCaptureState.isRecording] to `true`.
 *  3. The capture sheet (or any other Compose surface
 *     collecting the flow) renders the in-app "Stop voice"
 *     button because the StateFlow flipped.
 *  4. User taps the in-app button -> the sheet calls
 *     `context.startService(stopIntent)` -> the service's
 *     `handleStop` fires -> state flips to `false`.
 *
 * **What we test without an emulator:**
 *  - The state machine: the flow's initial value is `false`
 *    and flips correctly when the service updates it.
 *  - The [VoiceCaptureService] class is package-consistent
 *    with the manifest, and the `ACTION_STOP` constant is
 *    reachable from the in-app caller path.
 *
 * **What we don't test here:**
 *  - The actual Service lifecycle. The
 *    [VoiceCaptureService] requires `RECORD_AUDIO` +
 *    `FOREGROUND_SERVICE_MICROPHONE` and a live audio
 *    device; a Robolectric unit test cannot exercise
 *    `AudioRecord.startRecording()` reliably. The
 *    on-device drive case (`qa-voice-stop.xml`) is the
 *    authoritative UI test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VoiceCaptureInAppStopTest {

    @After
    fun resetState() {
        // The VoiceCaptureState is process-wide; tests can
        // pollute each other. Reset to a known initial value
        // after every test.
        VoiceCaptureState.setRecording(false)
    }

    @Test
    fun `initial state is not recording`() {
        // The flow's initial value must be `false` so a
        // brand-new user does not see the Stop button
        // for a frame between activity create and the
        // first service bind.
        assertFalse(
            "VoiceCaptureState should start as not recording",
            VoiceCaptureState.isRecording.value,
        )
    }

    @Test
    fun `state flips to recording when the service handleStart fires`() = runTest {
        // Simulate the service handleStart path.
        VoiceCaptureState.setRecording(true)
        assertTrue(
            "VoiceCaptureState should be true after handleStart",
            VoiceCaptureState.isRecording.value,
        )
        assertEquals(VoiceCapturePhase.STARTING, VoiceCaptureState.phase.value)
    }

    @Test
    fun `state flips back to not recording when the service handleStop fires`() = runTest {
        // Start recording, then stop. The flow's
        // `first()` readback gives us the current value
        // synchronously because it's a StateFlow.
        VoiceCaptureState.setRecording(true)
        assertTrue(VoiceCaptureState.isRecording.value)
        VoiceCaptureState.setRecording(false)
        assertFalse(VoiceCaptureState.isRecording.value)
        assertEquals(VoiceCapturePhase.IDLE, VoiceCaptureState.phase.value)
    }

    @Test
    fun `finishing keeps the capture active until the transcript is returned`() {
        VoiceCaptureState.setListening()
        VoiceCaptureState.setFinishing()

        assertTrue(VoiceCaptureState.isRecording.value)
        assertEquals(VoiceCapturePhase.FINISHING, VoiceCaptureState.phase.value)

        VoiceCaptureState.setRecording(false)
        assertFalse(VoiceCaptureState.isRecording.value)
        assertEquals(VoiceCapturePhase.IDLE, VoiceCaptureState.phase.value)
    }

    @Test
    fun `state flow emits the new value to a collector`() = runTest {
        // Tier 0.4: the in-app stop button relies on the
        // state flow emitting to its collector. We assert
        // the readback order matches the write order.
        VoiceCaptureState.setRecording(false)
        assertEquals(false, VoiceCaptureState.isRecording.first())
        VoiceCaptureState.setRecording(true)
        assertEquals(true, VoiceCaptureState.isRecording.first())
        VoiceCaptureState.setRecording(false)
        assertEquals(false, VoiceCaptureState.isRecording.first())
    }

    @Test
    fun `service action constants are reachable from the in-app caller`() {
        // The in-app stop-voice button on the capture
        // sheet fires a service intent with `action =
        // ACTION_STOP`. If a future commit renames the
        // constant without updating the call site, the
        // in-app button silently no-ops (the service
        // receives an unknown action and stops itself).
        assertEquals(
            "com.kaavalan.note.action.VOICE_STOP",
            VoiceCaptureService.ACTION_STOP,
        )
        assertEquals(
            "com.kaavalan.note.action.VOICE_START",
            VoiceCaptureService.ACTION_START,
        )
    }
}
