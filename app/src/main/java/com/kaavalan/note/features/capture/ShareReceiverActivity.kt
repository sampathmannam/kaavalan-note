package com.kaavalan.note.features.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kaavalan.note.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tier 0.3 (cleanup + ship-the-built): the v1.6.0 share-target
 * ingest activity. This is the rewrite of the v1.5.7
 * no-UI forwarder; the new shape is:
 *
 *  - **Compose-based**: the activity uses `setContent { }`
 *    even though the UI is empty -- a single transparent
 *    [Box] that fills the screen. The Compose layer is
 *    present so future Tier 2 work (a "Pick a person" picker
 *    sheet) can be added without a context switch.
 *  - **singleInstance launch mode**: declared in the
 *    manifest's `<activity-alias>`. The system only ever has
 *    one ShareReceiverActivity alive -- a second share intent
 *    re-uses the existing instance via [onNewIntent].
 *  - **Translucent, no UI shown**: the manifest theme is
 *    `Theme.KaavalanNote.Translucent.NoDisplay`. The user never
 *    sees a flash of the activity; the system just runs the
 *    code, forwards the payload to MainActivity, and the
 *    MainActivity's Compose tree takes over.
 *  - **Free-floating capture**: the receiver does NOT
 *    persist a `Person`. The shared text is forwarded to
 *    MainActivity, which pre-fills the capture sheet. The
 *    user picks a person (or creates a new one) at save
 *    time. This is the right UX: a "Save to <person>" picker
 *    would force a choice the user is not ready to make
 *    before they have seen the content.
 *
 * **No permission** is required -- `READ_EXTERNAL_STORAGE`
 * is not needed for the inbound image URI because the URI is
 * grant-scoped via the sender (Android 11+ scoped storage +
 * the share intent's `FLAG_GRANT_READ_URI_PERMISSION`).
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tier 0.3: the activity is Compose-based. The Composable
        // is a single transparent Box -- the activity has no
        // visible UI; the manifest theme is translucent.
        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                // Intentionally empty. The body of the work
                // is the intent-inspect + forward chain in
                // [handleIntent].
                ShareReceiverInvisible()
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleInstance: a second share intent re-uses this
        // activity instance. The new intent replaces the
        // existing one via setIntent so any subsequent reads
        // see the latest payload.
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (val payload = ShareIntake.inspect(intent)) {
            is ShareIntake.Result.Text -> {
                // Text payloads are forwarded directly. No
                // OCR step.
                forwardText(payload.text)
            }
            is ShareIntake.Result.Image -> {
                // Image payloads are OCR'd on a background
                // scope. The OCR call is suspending, so we
                // don't block the main thread; the activity
                // finishes as soon as the forward fires. If
                // the OCR is cancelled, we still forward an
                // empty string so the user lands somewhere.
                val uri = payload.uri
                val pending = CoroutineScope(Dispatchers.IO).launch {
                    forwardText(ocrTextOrEmpty(applicationContext, uri))
                }
                pending.invokeOnCompletion {
                    if (pending.isCancelled) forwardText("")
                }
            }
            null -> {
                // Unknown / invalid intent. Forward an empty
                // string so MainActivity opens the capture
                // sheet cleanly without a pre-fill.
                forwardText("")
            }
        }
    }

    private fun forwardText(text: String) {
        val forward = ShareIntake.buildForwardIntent(sharedText = text)
        forward.setClassName(this, MainActivity::class.java.name)
        startActivity(forward)
        finish()
    }
}

@Composable
private fun ShareReceiverInvisible() {
    // Tier 0.3: an intentionally-empty Composable. The
    // activity is translucent (see the manifest theme) so
    // this Box is invisible; the activity exists only to
    // host the intent-inspect + forward chain in
    // [ShareReceiverActivity.handleIntent].
    Box(modifier = Modifier.fillMaxSize())
}

/**
 * OCR a shared image, falling back to an empty pre-fill if it fails.
 *
 * [PhotoCapture.recognize] is documented to throw when the URI is
 * unreachable or the bytes are not a decodable image, and to leave the
 * empty-pre-fill fallback to its callers. This caller did not have one.
 * The call sat bare inside `CoroutineScope(Dispatchers.IO).launch`, a
 * scope with no parent and no `CoroutineExceptionHandler`, so the throw
 * went to the thread's uncaught handler and took the process down. The
 * `invokeOnCompletion` next to it does not help: it observes the failure
 * but does not consume it.
 *
 * [ShareReceiverActivity] is an exported share target, so the URI that
 * arrives belongs to the sender and not to us. Any installed app can
 * send an image share carrying a URI it never granted. An ordinary
 * share reaches the same throw by accident whenever the photo is
 * cloud-only, in a format ML Kit cannot decode, or backed by a temp
 * file the sender deletes before the OCR gets to it. Sharing a picture
 * into a notes app should at worst open an empty capture sheet.
 *
 * Cancellation is rethrown so the caller's `invokeOnCompletion` still
 * sees a cancelled job and forwards on its own.
 *
 * [recognize] is defaulted rather than called directly so the test can
 * drive the failure without ML Kit: `PhotoCapture.recognize` reaches
 * Play Services, which is not available to a JVM unit test, so a test
 * against the real call could only assert that *something* went wrong
 * on a path Robolectric decides.
 */
internal suspend fun ocrTextOrEmpty(
    context: Context,
    uri: Uri,
    recognize: suspend (Context, Uri) -> String = PhotoCapture::recognize,
): String = try {
    recognize(context, uri)
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Throwable) {
    Log.w(TAG, "OCR failed for a shared image; forwarding an empty pre-fill", failure)
    ""
}

private const val TAG = "KaavalanNoteShareReceiver"
