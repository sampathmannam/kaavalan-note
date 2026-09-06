package com.kaavalan.note.data.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9.0 (PROD-READINESS-P3-P1-#3): the
 * in-app update channel test. Mocks the
 * GitHub Releases API via Ktor's MockEngine
 * and asserts that the [UpdateChecker]
 * parses the response correctly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UpdateCheckerTest {

    // Both version comparisons below are derived from
    // BuildConfig.VERSION_NAME rather than hardcoded. The previous
    // version pinned literals ("v2.1.1" / "v2.1.2") that were only
    // correct while the app happened to be on 2.1.x: the 2.2.0 bump
    // made the "newer" release older than the running build and the
    // test failed for a reason that had nothing to do with
    // UpdateChecker. A release-blocking test must not need editing
    // on every version bump.
    private val runningVersion: String = com.kaavalan.note.BuildConfig.VERSION_NAME

    /** A version guaranteed to sort newer than whatever is running. */
    private fun aNewerVersionThanRunning(): String {
        val major = runningVersion.substringBefore('.').toIntOrNull() ?: 0
        return "${major + 1}.0.0"
    }

    @Test
    fun `check returns UpToDate when the latest tag matches the running version`() = runTest {
        val client = mockClient(releasesJson = singleReleaseJson(tag = "v$runningVersion"))
        val checker = UpdateChecker(httpClient = client)
        val result = checker.check()
        assertTrue("expected UpToDate, got $result", result is UpdateChecker.UpdateInfo.UpToDate)
    }

    @Test
    fun `check returns UpdateAvailable when the latest tag is newer`() = runTest {
        val newer = aNewerVersionThanRunning()
        val client = mockClient(releasesJson = singleReleaseJson(tag = "v$newer"))
        val checker = UpdateChecker(httpClient = client)
        val result = checker.check()
        assertTrue(
            "expected UpdateAvailable, got $result",
            result is UpdateChecker.UpdateInfo.UpdateAvailable,
        )
        result as UpdateChecker.UpdateInfo.UpdateAvailable
        assertEquals(newer, result.latestVersion)
    }

    @Test
    fun `check returns Unavailable when the API returns an error status`() = runTest {
        val client = mockClient(status = HttpStatusCode.InternalServerError)
        val checker = UpdateChecker(httpClient = client)
        val result = checker.check()
        assertTrue("expected Unavailable, got $result", result is UpdateChecker.UpdateInfo.Unavailable)
    }

    @Test
    fun `check returns Unavailable when the response is not a JSON array`() = runTest {
        val client = mockClient(releasesJson = "{\"message\":\"Not Found\"}")
        val checker = UpdateChecker(httpClient = client)
        val result = checker.check()
        assertTrue("expected Unavailable, got $result", result is UpdateChecker.UpdateInfo.Unavailable)
    }

    @Test
    fun `check returns Unavailable on an empty release list`() = runTest {
        val client = mockClient(releasesJson = "[]")
        val checker = UpdateChecker(httpClient = client)
        val result = checker.check()
        assertTrue("expected Unavailable, got $result", result is UpdateChecker.UpdateInfo.Unavailable)
    }

    private fun mockClient(
        releasesJson: String = "[]",
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpClient {
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(releasesJson),
                status = status,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        return HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    private fun singleReleaseJson(tag: String): String = """
        [
            {
                "tag_name": "$tag",
                "html_url": "https://github.com/sampathmannam/kaavalan-note/releases/tag/$tag",
                "published_at": "2026-08-21T00:00:00Z",
                "name": "Kaavalan note $tag",
                "prerelease": false,
                "draft": false
            }
        ]
    """.trimIndent()
}
