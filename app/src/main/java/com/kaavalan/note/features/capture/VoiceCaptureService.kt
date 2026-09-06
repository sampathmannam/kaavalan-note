package com.kaavalan.note.features.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.content.IntentCompat
import android.os.ResultReceiver
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.kaavalan.note.MainActivity
import com.kaavalan.note.R
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

/**
 * v1.6.1: voice capture via the system
 * `android.speech.SpeechRecognizer` service. Whisper.cpp is
 * gone -- no on-device LLM, no model file, no JNI library,
 * no PCM capture. The system service handles recording +
 * transcription natively (Google's on-device or cloud STT
 * depending on the device + locale).
 *
 * Lifecycle:
 *  1. Activity calls [start] with a [ResultReceiver] (the
 *     Activity's `MainActivity` typically). The caller must
 *     already hold `RECORD_AUDIO`.
 *  2. Service starts as a foreground service with
 *     `foregroundServiceType=microphone`, posts a sticky
 *     notification, and calls `SpeechRecognizer.startListening`.
 *  3. The [RecognitionListener] receives partial results
 *     (logged, not surfaced) and a final result. The final
 *     text is delivered to the [ResultReceiver] via
 *     `RESULT_OK` + a `KEY_TEXT` bundle. Errors are delivered
 *     as `RESULT_ERROR` with a `KEY_ERROR` message.
 *  4. The service stops itself and tears down the notification.
 *
 * **Why keep a Service at all:** the system SpeechRecognizer
 * itself does not require a foreground service to record.
 * The Service is preserved so:
 *   - the existing notification UX is unchanged for users
 *     who already rely on the "swipe to stop" affordance
 *   - the RECORD_AUDIO perm is foregrounded (the user can
 *     see the mic is live) on Android 14+ which enforces
 *     `foregroundServiceType=microphone`
 *   - the existing `VoiceCaptureState` process-wide
 *     StateFlow wiring (Tier 0.4) keeps working -- the
 *     in-app capture sheet renders the in-app Stop button
 *     from the same state source
 *
 * **Threading:** all SpeechRecognizer callbacks are on the
 * main thread. We deliver the result + stop the service on
 * the same thread; `stopSelf()` is safe on main.
 */
@AndroidEntryPoint
class VoiceCaptureService : Service() {

    private var speechRecognizer: SpeechRecognizer? = null
    private var resultReceiver: ResultReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            else -> {
                Log.w(TAG, "unknown action: ${intent?.action}")
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        // v2.1.2 (crash fix): use the AndroidX compat shim.
        //
        // v1.6.7 replaced the deprecated `getParcelableExtra(String)`
        // with the typed two-argument overload
        // `getParcelableExtra(String, Class)` to silence the
        // deprecation warning. That overload was added in **API 33**,
        // and this module's `minSdk` is 26 — so on every device below
        // Android 13 this line threw `NoSuchMethodError` and took the
        // foreground service down as soon as the user started a voice
        // capture. Voice is one of the three primary capture modes,
        // and pre-Android-13 devices are a large share of the target
        // user base, so this was a crash for most users on a core
        // flow. Android Lint reported it as the sole `NewApi` error,
        // but the CI lint job ran with `continue-on-error: true`, so
        // nothing surfaced it.
        //
        // `IntentCompat.getParcelableExtra` (androidx.core 1.10+;
        // this project is on 1.13.1) calls the typed overload on
        // API 33+ and the checked-cast legacy path below it, keeping
        // the type safety v1.6.7 wanted without the API floor.
        val receiver: ResultReceiver? = IntentCompat.getParcelableExtra(
            intent,
            EXTRA_RESULT_RECEIVER,
            ResultReceiver::class.java,
        )
        resultReceiver = receiver
        // Tier 0.4: flip the process-wide state so the
        // in-app capture sheet can render a Stop button.
        VoiceCaptureState.setRecording(true)
        startForegroundWithNotification()
        startRecognizing()
    }

    private fun startRecognizing() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            deliverError("Speech recognition is not available on this device.")
            VoiceCaptureState.setRecording(false)
            stopForegroundCompat()
            stopSelf()
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            speechRecognizer = it
            it.setRecognitionListener(KaavalanRecognitionListener())
        }
        val listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { recognizer.startListening(listenIntent) }
            .onFailure { e ->
                Log.e(TAG, "startListening failed", e)
                deliverError(e.message ?: "Could not start voice recognition.")
                VoiceCaptureState.setRecording(false)
                stopForegroundCompat()
                stopSelf()
            }
    }

    private fun handleStop() {
        // `stopListening` fires `onResults` (or `onError` if
        // nothing was captured). The listener delivers the
        // text and stops the service. The in-app Stop button
        // shares this code path with the notification action.
        runCatching { speechRecognizer?.stopListening() }
            .onFailure { e -> Log.w(TAG, "stopListening failed", e) }
        // Belt + suspenders: if the listener never fires
        // (some devices are flaky), still tear down within
        // a few hundred ms. The user sees the recording
        // stop in the notification either way.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (VoiceCaptureState.isRecording.value) {
                Log.w(TAG, "listener never fired, forcing teardown")
                VoiceCaptureState.setRecording(false)
                stopForegroundCompat()
                stopSelf()
            }
        }, STOP_TIMEOUT_MS)
    }

    private fun startForegroundWithNotification() {
        val channelId = ensureChannel()
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VoiceCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.voice_capture_title))
            .setContentText(getString(R.string.voice_capture_text))
            .setSmallIcon(R.drawable.ic_voice_notification)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .addAction(
                // v1.6.7: addAction(icon, title, intent) is deprecated;
                // replaced with the single-arg addAction(Notification.Action)
                // which is the supported API 23+ path. The
                // Notification.Action(int, CharSequence, PendingIntent)
                // constructor is itself deprecated in API 23; the
                // Builder(Icon, CharSequence, PendingIntent) form is
                // the supported replacement. Icon is nullable and
                // left null here so the system renders a generic
                // action affordance (matches the previous int=0
                // behavior, which also produced no custom icon).
                Notification.Action.Builder(
                    null,
                    getString(R.string.voice_capture_stop),
                    stopIntent,
                ).build(),
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun ensureChannel(): String {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.voice_capture_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
                nm.createNotificationChannel(ch)
            }
        }
        return CHANNEL_ID
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun deliverText(text: String) {
        val receiver = resultReceiver ?: return
        val bundle = Bundle().apply { putString(KEY_TEXT, text) }
        receiver.send(RESULT_OK, bundle)
    }

    private fun deliverError(message: String) {
        val receiver = resultReceiver ?: return
        val bundle = Bundle().apply { putString(KEY_ERROR, message) }
        receiver.send(RESULT_ERROR, bundle)
    }

    override fun onDestroy() {
        // Tier 0.4: reset the in-app state. If the service
        // is killed by the system (e.g. memory pressure)
        // the state must reflect "not recording" so the
        // capture sheet's Stop button hides.
        VoiceCaptureState.setRecording(false)
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        super.onDestroy()
    }

    /**
     * The [RecognitionListener] that funnels the system
     * SpeechRecognizer callbacks into the existing
     * ResultReceiver contract. The previous Whisper
     * implementation had its own AudioRecord + PCM
     * capture; the system service does all of that
     * internally.
     */
    private inner class KaavalanRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            // v1.6.1: partial results are not surfaced to
            // the capture sheet. The TextField is updated
            // only when the final transcript arrives so the
            // user sees a single, clean "your speech as
            // text" event rather than flickering partial
            // strings.
        }
        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isBlank()) {
                deliverError("No speech detected.")
            } else {
                deliverText(text)
            }
            VoiceCaptureState.setRecording(false)
            stopForegroundCompat()
            stopSelf()
        }
        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                SpeechRecognizer.ERROR_CLIENT -> "Client error."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied."
                SpeechRecognizer.ERROR_NETWORK -> "Network error."
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout."
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy."
                SpeechRecognizer.ERROR_SERVER -> "Server error."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input."
                else -> "Voice recognition failed (code $error)."
            }
            deliverError(message)
            VoiceCaptureState.setRecording(false)
            stopForegroundCompat()
            stopSelf()
        }
    }

    companion object {
        private const val TAG = "KaavalanNoteVoice"

        // v1.6.1: belt-and-suspenders teardown timeout. If
        // the system SpeechRecognizer never fires its final
        // callback after `stopListening`, we tear down
        // ourselves after this delay. The user sees the
        // recording end either way.
        private const val STOP_TIMEOUT_MS = 1500L

        const val ACTION_START = "com.kaavalan.note.action.VOICE_START"
        const val ACTION_STOP = "com.kaavalan.note.action.VOICE_STOP"

        const val EXTRA_RESULT_RECEIVER = "result_receiver"

        const val KEY_TEXT = "text"
        const val KEY_ERROR = "error"

        const val RESULT_OK = 1
        const val RESULT_ERROR = 2

        const val CHANNEL_ID = "voice_capture"
        const val NOTIFICATION_ID = 1011

        /**
         * Convenience for callers. The receiver is
         * delivered the transcript (or error) when the
         * service finishes. The caller must hold the
         * `RECORD_AUDIO` runtime permission before
         * invoking.
         */
        fun start(context: Context, receiver: ResultReceiver) {
            val intent = Intent(context, VoiceCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_RECEIVER, receiver)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Convenience to stop the service. The service
         * also stops itself when recognition completes;
         * this is the user-cancel path.
         */
        fun stop(context: Context) {
            val intent = Intent(context, VoiceCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
