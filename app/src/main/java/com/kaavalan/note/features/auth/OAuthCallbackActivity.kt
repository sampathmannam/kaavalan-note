package com.kaavalan.note.features.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.kaavalan.note.data.backup.GoogleOAuthClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * v2.1.0 (PM rating): the OAuth callback activity.
 * Google redirects the user to
 * `kaavalan-note://oauth-callback?code=...&state=...` after
 * they sign in + grant the `drive.appdata` scope.
 * The activity (declared in the manifest with the
 * matching intent filter) catches the intent,
 * extracts the auth code + state, validates the
 * state against the persisted value (the OAuth
 * `state` parameter from the Authorization Code
 * with PKCE flow), calls
 * [GoogleOAuthClient.completeSignIn] to exchange
 * the code for an access + refresh token, and
 * finishes.
 *
 * **v2.1.1 (security): the `state` parameter check.**
 * The OAuth flow generates a random 32-byte `state`
 * in [GoogleOAuthClient.signIn] and persists it to a
 * private file. The callback reads the same file
 * (via [GoogleOAuthClient.consumeOAuthVerifier]) and
 * compares. If the inbound `state` doesn't match
 * (or is absent), we reject the redirect without
 * exchanging the code. This blocks the
 * "any-installed-app-can-fire-kaavalan-note://oauth-callback
 * with-an-attacker's-code" attack that v2.1.0
 * allowed.
 *
 * **The flow is single-task.** The user is bounced
 * out of the Settings sheet to the Chrome Custom
 * Tab for the OAuth page, then back to this
 * activity. The activity is `singleTop` so a
 * re-entry (e.g. a double-tap on the sign-in
 * button) doesn't stack two copies.
 *
 * **The activity is a transparent overlay.** It
 * finishes immediately after the token exchange
 * completes; the user lands back in the Settings
 * sheet which polls the [GoogleOAuthClient.isSignedIn]
 * flag on resume and re-renders the "Signed in as
 * ..." row.
 */
@AndroidEntryPoint
class OAuthCallbackActivity : ComponentActivity() {

    @Inject lateinit var oauth: GoogleOAuthClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action != Intent.ACTION_VIEW) {
            Log.w(TAG, "Rejected OAuth callback with unexpected action")
            finish()
            return
        }
        val data = intent.data ?: run {
            Log.w(TAG, "OAuth callback launched without data URI")
            finish()
            return
        }
        if (!isExpectedRedirect(data)) {
            Log.w(TAG, "Rejected OAuth callback with unexpected redirect URI")
            finish()
            return
        }
        val code = data.getQueryParameter("code")
        if (code == null) {
            Log.w(TAG, "OAuth callback did not contain an authorization code")
            finish()
            return
        }
        // v2.1.1 (security): validate `state` against the
        // persisted value. If absent or mismatch, we
        // reject the redirect without calling
        // [GoogleOAuthClient.completeSignIn]. The state
        // file is deleted on success; if a malicious app
        // tries to replay a code, the state file is
        // already gone (consumed by the legitimate flow).
        val inboundState = data.getQueryParameter("state")
        val verifier = inboundState?.let(oauth::consumeOAuthVerifier)
        if (verifier == null) {
            Log.w(
                TAG,
                "Rejected OAuth callback with absent, stale, or mismatched state",
            )
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                oauth.completeSignIn(code, verifier)
                Log.i(TAG, "OAuth sign-in complete")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "OAuth token exchange failed (${e::class.java.simpleName})")
            } finally {
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "KaavalanNoteOAuthCallback"

        internal fun isExpectedRedirect(uri: Uri): Boolean {
            val expected = Uri.parse(GoogleOAuthClient.REDIRECT_URI)
            return uri.scheme == expected.scheme &&
                uri.host == expected.host &&
                uri.port == expected.port &&
                uri.path == expected.path &&
                uri.fragment == null
        }
    }
}
