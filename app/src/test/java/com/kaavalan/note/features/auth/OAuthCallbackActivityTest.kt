package com.kaavalan.note.features.auth

import android.net.Uri
import com.kaavalan.note.data.backup.GoogleOAuthClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OAuthCallbackActivityTest {

    @Test
    fun `redirect validation accepts only the configured callback origin and path`() {
        val valid = Uri.parse(GoogleOAuthClient.REDIRECT_URI).buildUpon()
            .appendQueryParameter("code", "code")
            .appendQueryParameter("state", "state")
            .build()

        assertTrue(OAuthCallbackActivity.isExpectedRedirect(valid))
        assertFalse(
            OAuthCallbackActivity.isExpectedRedirect(
                Uri.parse("other-scheme://oauth-callback?code=code&state=state"),
            ),
        )
        assertFalse(
            OAuthCallbackActivity.isExpectedRedirect(
                Uri.parse("kaavalan-note://other-host?code=code&state=state"),
            ),
        )
        assertFalse(
            OAuthCallbackActivity.isExpectedRedirect(
                Uri.parse("kaavalan-note://oauth-callback/other?code=code&state=state"),
            ),
        )
        assertFalse(OAuthCallbackActivity.isExpectedRedirect(valid.buildUpon().fragment("x").build()))
    }
}
