package com.kaavalan.note.data.backup

import android.content.Context
import com.kaavalan.note.data.auth.SecurePreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for one-shot OAuth state validation and PKCE verifier consumption.
 *
 * The OAuth `state` + `code_verifier` are persisted to
 * `filesDir/oauth_state.tmp` in [GoogleOAuthClient.signIn] and
 * read in the [com.kaavalan.note.features.auth.OAuthCallbackActivity]
 * (which then forwards the values back to
 * [GoogleOAuthClient.completeSignIn]). The tests pin the
 * happy path (state + verifier round-trip) and the
 * failure modes (missing file / malformed file / delete).
 *
 * The `state` parameter is the OAuth 2.0
 * authorization-code-injection defence (RFC 6749 §10.12);
 * the `code_verifier` is the PKCE binding (RFC 7636).
 * Without these, any installed app on the device can fire
 * `kaavalan-note://oauth-callback?code=ATTACKER_CODE` and have
 * Kaavalan note exchange the attacker's auth code.
 *
 * Robolectric is used for the [Context] (the real Android
 * `filesDir` is internal). [SecurePreferences] is mocked
 * (via [mockk]) because it depends on AndroidKeyStore,
 * which Robolectric cannot fully emulate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GoogleOAuthClientStateTest {

    private lateinit var context: Context
    private lateinit var client: GoogleOAuthClient
    private val securePreferences: SecurePreferences = mockk(relaxed = true)
    private val httpClient: HttpClient = HttpClient(
        MockEngine { _ ->
            respond(
                content = ByteReadChannel("""{"access_token":"x","refresh_token":"y","expires_in":3600}"""),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        },
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Clean any leftover state file from a previous run.
        File(context.filesDir, "oauth_state.tmp").delete()
        client = GoogleOAuthClient(
            context = context,
            httpClient = httpClient,
            securePreferences = securePreferences,
        )
    }

    @After
    fun tearDown() {
        File(context.filesDir, "oauth_state.tmp").delete()
    }

    @Test
    fun `consumeOAuthVerifier returns null when the state file is absent`() {
        // No signIn() called — the file should not exist.
        assertNull(client.consumeOAuthVerifier(validState('a')))
    }

    @Test
    fun `consumeOAuthVerifier rejects and removes a malformed state file`() {
        val file = File(context.filesDir, "oauth_state.tmp")
        file.writeText("only-one-line-no-newline")
        assertNull(client.consumeOAuthVerifier(validState('a')))
        assertFalse(file.exists())
    }

    @Test
    fun `consumeOAuthVerifier returns verifier and removes the one-shot file on match`() {
        val expectedState = validState('a')
        val expectedVerifier = validState('v')
        val file = File(context.filesDir, "oauth_state.tmp")
        file.writeText("$expectedState\n$expectedVerifier")

        assertEquals(expectedVerifier, client.consumeOAuthVerifier(expectedState))
        assertFalse(file.exists())
        assertNull(client.consumeOAuthVerifier(expectedState))
    }

    @Test
    fun `state mismatch does not cancel a legitimate pending sign-in`() {
        val expectedState = validState('a')
        val expectedVerifier = validState('v')
        val file = File(context.filesDir, "oauth_state.tmp")
        file.writeText("$expectedState\n$expectedVerifier")

        assertNull(client.consumeOAuthVerifier(validState('b')))
        assertTrue(file.exists())
        assertEquals(expectedVerifier, client.consumeOAuthVerifier(expectedState))
    }

    @Test
    fun `invalid oversized inbound state is rejected without reading secrets`() {
        val file = File(context.filesDir, "oauth_state.tmp")
        file.writeText("${validState('a')}\n${validState('v')}")

        assertNull(client.consumeOAuthVerifier("x".repeat(10_000)))
        assertTrue(file.exists())
    }

    @Test
    fun `completeSignIn accepts a consumed valid verifier`() = runTest {
        client.completeSignIn("dummy-auth-code", validState('v'))
    }

    private fun validState(character: Char): String = character.toString().repeat(43)
}
