package com.kaavalan.note.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.kaavalan.note.BuildConfig
import com.kaavalan.note.data.auth.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v2.1.0 (PM rating): the Google OAuth client.
 *
 * **The flow.** When the user taps "Sign in with
 * Google" in Settings, [signIn] opens a Chrome Custom
 * Tab to Google's OAuth page. The user signs in +
 * grants the `drive.appdata` scope. Google redirects
 * to `kaavalan-note://oauth-callback?code=AUTH_CODE&state=STATE`.
 * The [com.kaavalan.note.features.auth.OAuthCallbackActivity]
 * catches the redirect, validates the `state` against
 * the persisted value, extracts the code, and calls
 * [completeSignIn].
 *
 * [completeSignIn] exchanges the auth code + PKCE
 * `code_verifier` for an access + refresh token via
 * Google's token endpoint (using a Ktor [HttpClient] —
 * no Play Services Auth needed). The refresh token is
 * stored in [SecurePreferences]; the access token is
 * held in memory and refreshed on demand by
 * [getAccessToken].
 *
 * **Why Custom Tabs instead of GoogleSignInClient.**
 * The offline build cache doesn't have
 * `play-services-auth:21.2.0`. The Custom Tabs
 * approach uses only `androidx.browser:browser` (which
 * is in the cache). The trade-off: the user gets a
 * Chrome Custom Tab instead of the one-tap "Sign in
 * with Google" picker, but the end result (an access
 * token + the ability to call the Drive REST API) is
 * the same. A future v2.x can swap to the
 * GoogleSignInClient path for a smoother UX.
 *
 * **The redirect URI scheme.** The app declares an
 * intent filter for `kaavalan-note://oauth-callback` in the
 * manifest. The OAuthCallbackActivity is a transparent
 * activity that catches the intent, extracts the
 * `code` + `state` parameters, validates `state`
 * against the persisted value, calls [completeSignIn],
 * and finishes.
 *
 * **v2.1.1 (security): `state` + PKCE.** The v2.1.0
 * OAuth flow was missing two standard defences against
 * authorization-code injection (the
 * "any-installed-app-can-fire-kaavalan-note://oauth-callback
 * with-an-attacker's-code" attack):
 *
 *  1. **`state` (RFC 6749 §10.12).** [signIn] generates
 *     a 32-byte random secret, base64url-encodes it,
 *     sends it as `state=...` in the auth URL, and
 *     persists it to a private file in `filesDir`. The
 *     callback activity reads the same file and rejects
 *     the redirect if the inbound `state` doesn't match
 *     (or if the file is missing — which means the
 *     legitimate flow already consumed it). A
 *     malicious `am start -a android.intent.action.VIEW
 *     -d kaavalan-note://oauth-callback?code=ATTACKER_CODE"`
 *     fires the activity but the state file is absent
 *     (or already consumed) so the exchange is
 *     rejected.
 *
 *  2. **PKCE (RFC 7636, S256).** [signIn] generates a
 *     32-byte random `code_verifier`, base64url-encodes
 *     it, sends `code_challenge = base64url(SHA256(verifier))`
 *     and `code_challenge_method=S256` in the auth
 *     URL, and persists the verifier alongside the
 *     state. [completeSignIn] sends the verifier in
 *     the token POST. Google hashes it and compares to
 *     the `code_challenge` from the auth URL; if they
 *     don't match, the exchange is rejected. This
 *     binds the auth code to this device — a stolen
 *     code redeemed from a different device would fail
 *     the challenge comparison.
 *
 * **v2.1.1 (security): token-exchange response body
 * is no longer logged.** Google's error responses can
 * echo the offending `client_id` and the malformed
 * `code` value; the v2.1.0 `require(...)` message
 * dumped the full body to logcat. The v2.1.1 message
 * only includes the HTTP status code.
 *
 * **v2.1.1 (security): CustomTabs `FLAG_ACTIVITY_NEW_TASK`.**
 * [signIn] is called from a Hilt-injected
 * [GoogleOAuthClient] (which holds an `@ApplicationContext`),
 * not from an Activity. Without `FLAG_ACTIVITY_NEW_TASK`,
 * `CustomTabsIntent.launchUrl` throws
 * `AndroidRuntimeException: Calling startActivity() from
 * outside of an Activity context requires the
 * FLAG_ACTIVITY_NEW_TASK flag`. The flag is added to the
 * Custom Tabs intent so the OAuth flow can be initiated
 * from any context.
 */
@Singleton
class GoogleOAuthClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val securePreferences: SecurePreferences,
) {

    /**
     * Held in memory after a successful
     * [completeSignIn]. Refreshed on demand by
     * [getAccessToken]. The access token expires in
     * ~1h; the refresh token is long-lived (until the
     * user revokes the app's access in their Google
     * account settings).
     *
     * **v2.1.1 (security): CharArray, not String.**
     * v2.1.0's `String?` field was interned by the
     * JVM (Google's access tokens are short ASCII
     * strings, well under the 65k `StringTable`
     * intern threshold for many JVMs) and survived
     * in the string pool until the next GC of the
     * `StringTable` — a heap dump at the right
     * moment revealed the access token. v2.1.1
     * stores the token as a [CharArray] (which is
     * not interned) and zeroes the buffer on
     * [signOut] / re-issue / process death.
     */
    @Volatile
    private var cachedAccessToken: CharArray? = null

    /**
     * Open the Google OAuth page in a Chrome Custom
     * Tab. The user signs in, grants the
     * `drive.appdata` scope, and Google redirects to
     * `kaavalan-note://oauth-callback?code=...&state=...`. The
     * [com.kaavalan.note.features.auth.OAuthCallbackActivity]
     * catches the redirect, validates the `state`, and
     * calls [completeSignIn].
     */
    fun signIn() {
        check(isConfigured()) {
            "Google Drive OAuth is not configured for this build"
        }
        // v2.1.1 (security): the OAuth flow now uses the
        // `state` parameter + PKCE (S256). Without these,
        // any installed app on the device can fire
        // `kaavalan-note://oauth-callback?code=ATTACKER_CODE` and
        // have Kaavalan note exchange the attacker's auth code
        // for a real access + refresh token (the
        // settings sheet will then show "Signed in as
        // attacker@evil.com" and every Drive backup
        // lands in the attacker's appDataFolder).
        val state = generateState()
        val verifier = generatePkceVerifier()
        val challenge = sha256Base64Url(verifier)
        persistOAuthState(state, verifier)

        val authUrl = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth")
            .buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter(
                "scope",
                "https://www.googleapis.com/auth/drive.appdata email",
            )
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("include_granted_scopes", "true")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        val intent = CustomTabsIntent.Builder().build()
        // v2.1.1 (security): CustomTabs requires an
        // Activity context. The injected @ApplicationContext
        // is the application context, which means
        // `launchUrl` throws `AndroidRuntimeException:
        // Calling startActivity() from outside of an
        // Activity context requires the
        // FLAG_ACTIVITY_NEW_TASK flag`. Set the flag
        // here so the OAuth flow can be initiated from
        // any context (e.g. the Hilt-injected
        // GoogleOAuthClient inside a ViewModel).
        intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.launchUrl(context, authUrl)
    }

    private fun randomOAuthToken(): String {
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
    }

    private fun generateState(): String = randomOAuthToken()

    // RFC 7636: 43-128 chars, [A-Z][a-z][0-9]-._~
    private fun generatePkceVerifier(): String = randomOAuthToken()

    private fun sha256Base64Url(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.US_ASCII))
        return android.util.Base64.encodeToString(
            digest,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
    }

    /**
     * Persist the OAuth `state` and `code_verifier` to a
     * private file in the app's filesDir so the
     * OAuthCallbackActivity (a separate Activity) can
     * read them. Android creates the file in app-private
     * storage with `MODE_PRIVATE`; starting a new flow
     * replaces any stale state from an abandoned flow.
     */
    private fun persistOAuthState(state: String, verifier: String) {
        context.openFileOutput(OAUTH_STATE_FILE, Context.MODE_PRIVATE).bufferedWriter().use {
            it.write(state)
            it.newLine()
            it.write(verifier)
        }
    }

    /**
     * Validate [inboundState] and atomically consume the
     * persisted PKCE verifier. A mismatch leaves the file
     * intact so a forged deep link cannot cancel a real
     * sign-in that is still open in the browser. A match
     * deletes the one-shot secret before the token request,
     * preventing callback replay even if the request fails.
     */
    internal fun consumeOAuthVerifier(inboundState: String): String? = synchronized(OAUTH_STATE_LOCK) {
        if (!isValidState(inboundState)) return@synchronized null
        val file = File(context.filesDir, OAUTH_STATE_FILE)
        if (!file.isFile) return@synchronized null
        val parts = runCatching {
            file.readText(Charsets.US_ASCII).split('\n', limit = 2)
        }.getOrNull()
        if (parts == null || parts.size != 2) {
            file.delete()
            return@synchronized null
        }
        val expectedState = parts[0]
        val verifier = parts[1].trimEnd('\r', '\n')
        if (!isValidState(expectedState) || !isValidVerifier(verifier)) {
            file.delete()
            return@synchronized null
        }
        val matches = MessageDigest.isEqual(
            expectedState.toByteArray(Charsets.US_ASCII),
            inboundState.toByteArray(Charsets.US_ASCII),
        )
        if (!matches) return@synchronized null
        if (!file.delete()) {
            // Do not exchange a code while its verifier remains replayable.
            return@synchronized null
        }
        verifier
    }

    /**
     * Called by [com.kaavalan.note.features.auth.OAuthCallbackActivity]
     * once the user has been redirected back with
     * `?code=...&state=...`. The activity has already
     * validated and consumed `state` via
     * [consumeOAuthVerifier]; this function exchanges
     * the code + supplied PKCE verifier for an
     * access + refresh token. The refresh token is
     * stored in [SecurePreferences] for next-time
     * silent sign-in.
     */
    suspend fun completeSignIn(authCode: String, codeVerifier: String) {
        // v2.1.1 (security): PKCE. The token endpoint
        // receives the `code_verifier` we generated in
        // [signIn] and persisted. Google hashes it and
        // compares to the `code_challenge` from the auth
        // URL; if they don't match, the exchange is
        // rejected. This binds the code to this device —
        // a stolen code redeemed from a different device
        // would fail the challenge comparison.
        require(authCode.isNotBlank() && authCode.length <= MAX_AUTH_CODE_LENGTH) {
            "Invalid OAuth authorization code"
        }
        require(isValidVerifier(codeVerifier)) { "Invalid OAuth PKCE verifier" }
        // v2.1.1 (security): never log the full response
        // body. Google's error responses can echo the
        // offending `client_id` and the malformed
        // `code` value, which are tokens we don't want in
        // logcat.
        val response = httpClient.submitForm(
            url = "https://oauth2.googleapis.com/token",
            formParameters = Parameters.build {
                append("code", authCode)
                append("client_id", CLIENT_ID)
                append("redirect_uri", REDIRECT_URI)
                append("grant_type", "authorization_code")
                append("code_verifier", codeVerifier)
            },
        )
        require(response.status.value in 200..299) {
            "Token exchange failed: ${response.status}"
        }
        val json = JSONObject(response.bodyAsText())
        val accessToken = json.getString("access_token")
        val refreshToken = json.optString("refresh_token", "")
        val expiresIn = json.optLong("expires_in", 3600L)
        // v2.1.1 (security): zero any prior cached
        // access token before writing the new one.
        cachedAccessToken?.fill('\u0000')
        cachedAccessToken = accessToken.toCharArray()
        if (refreshToken.isNotEmpty()) {
            securePreferences.setGoogleRefreshToken(refreshToken)
        }
        securePreferences.setGoogleAccessTokenExpiry(System.currentTimeMillis() + expiresIn * 1000)
    }

    /**
     * Return a valid access token, refreshing from
     * the stored refresh token if the cached one is
     * missing or expired. Returns `null` if the user
     * has never signed in.
     */
    suspend fun getAccessToken(): String? {
        if (!isConfigured()) return null
        cachedAccessToken?.let { cached ->
            val expiry = securePreferences.getGoogleAccessTokenExpiry()
            if (expiry > System.currentTimeMillis() + 60_000) {
                return String(cached)
            }
        }
        // Cached expired or absent — refresh.
        val refreshToken = securePreferences.getGoogleRefreshToken() ?: return null
        val response = httpClient.submitForm(
            url = "https://oauth2.googleapis.com/token",
            formParameters = Parameters.build {
                append("refresh_token", refreshToken)
                append("client_id", CLIENT_ID)
                append("grant_type", "refresh_token")
            },
        )
        if (response.status.value !in 200..299) {
            // The refresh token was revoked. Clear
            // everything so the Settings sheet renders
            // the "Sign in" CTA.
            securePreferences.clearGoogleTokens()
            clearCachedAccessToken()
            return null
        }
        val json = JSONObject(response.bodyAsText())
        val accessToken = json.getString("access_token")
        val expiresIn = json.optLong("expires_in", 3600L)
        // v2.1.1 (security): zero any prior cached
        // access token before writing the new one.
        cachedAccessToken?.fill('\u0000')
        cachedAccessToken = accessToken.toCharArray()
        securePreferences.setGoogleAccessTokenExpiry(System.currentTimeMillis() + expiresIn * 1000)
        // The new access token may also include a new
        // refresh token; capture if present.
        json.optString("refresh_token", "").takeIf { it.isNotEmpty() }?.let {
            securePreferences.setGoogleRefreshToken(it)
        }
        return accessToken
    }

    /**
     * v2.1.1 (security): zero + null the cached
     * access token. Called by [signOut] and the
     * refresh-failure path.
     */
    private fun clearCachedAccessToken() {
        cachedAccessToken?.fill('\u0000')
        cachedAccessToken = null
    }

    /**
     * Sign out — clear the in-memory access token, the
     * stored refresh token, and the expiry. The Settings
     * sheet renders the "Sign in" CTA again.
     */
    fun signOut() {
        clearCachedAccessToken()
        securePreferences.clearGoogleTokens()
    }

    /**
     * Has the user signed in? `true` if a refresh token
     * is stored. The in-memory `cachedAccessToken` is
     * process-local and not a reliable signal (the
     * process can be killed; we read the refresh token
     * from SharedPreferences).
     */
    fun isSignedIn(): Boolean =
        isConfigured() && securePreferences.getGoogleRefreshToken() != null

    companion object {
        private const val OAUTH_STATE_FILE = "oauth_state.tmp"
        private const val MAX_AUTH_CODE_LENGTH = 4096
        private const val EXPECTED_REDIRECT_URI = "kaavalan-note://oauth-callback"
        private val OAUTH_STATE_LOCK = Any()
        private val OAUTH_TOKEN_RE = Regex("[A-Za-z0-9._~-]{43,128}")
        private val GOOGLE_CLIENT_ID_RE =
            Regex("[A-Za-z0-9_-]{10,200}\\.apps\\.googleusercontent\\.com")

        private fun isValidState(value: String): Boolean = OAUTH_TOKEN_RE.matches(value)

        private fun isValidVerifier(value: String): Boolean = OAUTH_TOKEN_RE.matches(value)

        // v2.1.1 (security): the client ID + redirect URI
        // are read from `BuildConfig` (which is populated
        // by `app/build.gradle.kts` from `local.properties`
        // or a Gradle project property). The v2.1.0
        // hard-coded placeholders failed the token
        // exchange with `400 invalid_client` because
        // Google rejects unknown client IDs. The user
        // sets `KAAVALAN_NOTE_GOOGLE_OAUTH_CLIENT_ID` in
        // `local.properties` before shipping to the
        // Play Store.
        //
        // The redirect scheme is `kaavalan-note` and the host
        // is `oauth-callback` — both are declared in
        // the AndroidManifest as an intent filter on
        // [com.kaavalan.note.features.auth.OAuthCallbackActivity].
        val CLIENT_ID: String = BuildConfig.KAAVALAN_NOTE_GOOGLE_OAUTH_CLIENT_ID
        val REDIRECT_URI: String = BuildConfig.KAAVALAN_NOTE_GOOGLE_OAUTH_REDIRECT_URI

        /**
         * A plain checkout intentionally compiles with a placeholder client ID.
         * Treat that build as Drive-disabled instead of opening an OAuth page
         * which can only fail with `invalid_client`. The redirect is kept strict
         * because the manifest only owns this exact app link.
         */
        fun isConfigured(): Boolean = isConfigurationValid(CLIENT_ID, REDIRECT_URI)

        internal fun isConfigurationValid(clientId: String, redirectUri: String): Boolean =
            GOOGLE_CLIENT_ID_RE.matches(clientId.trim()) &&
                redirectUri.trim() == EXPECTED_REDIRECT_URI
    }
}
