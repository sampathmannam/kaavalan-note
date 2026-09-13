package com.kaavalan.note.data.update

import com.kaavalan.note.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * v1.9.0 (PROD-READINESS-P3-P1-#3): the in-app
 * update channel. Queries the GitHub Releases
 * API for the latest `v*` tag, compares it to
 * the running [BuildConfig.VERSION_NAME], and
 * returns an [UpdateInfo] the Settings sheet
 * surfaces as "Kaavalan note vX.Y.Z is available. You
 * are on vA.B.C."
 *
 * **Why GitHub Releases, not a custom server.**
 *  - No new infra: a public GitHub repo already
 *    has the release list.
 *  - The user can audit the URL in
 *    `docs/privacy-policy.md` (no third-party
 *    endpoint).
 *  - A `v*` tag is required to release, so the
 *    API is the source of truth for "is there
 *    a newer build?".
 *
 * **Failure modes.**
 *  - Network unreachable: returns
 *    [UpdateInfo.Unavailable] with the
 *    "Could not reach the update server"
 *    reason. The Settings sheet surfaces a
 *    snackbar.
 *  - Rate-limited (60 requests/hour/IP for
 *    unauthenticated GitHub): same
 *    [UpdateInfo.Unavailable] return. A
 *    user-side fix is to wait an hour; a
 *    v2.x can move to a GitHub PAT for the
 *    higher rate limit.
 *  - Repo not found / API changed: same
 *    [UpdateInfo.Unavailable].
 *
 * **Privacy.** The request is a plain GET to
 * `api.github.com`. No auth, no headers beyond
 * the standard `User-Agent: KaavalanNote/<version>`.
 * GitHub's standard server logs apply; see
 * GitHub's privacy policy for what they collect.
 * The user has explicitly tapped "Check for
 * updates" so the network call is consented.
 */
class UpdateChecker(
    private val httpClient: HttpClient,
    private val repoOwner: String = "sampathmannam",
    private val repoName: String = "kaavalan-note",
) {

    sealed class UpdateInfo {
        /**
         * No newer version is available — the
         * running build is the latest released
         * tag.
         */
        data class UpToDate(val currentVersion: String) : UpdateInfo()

        /**
         * A newer version is available. The
         * Settings sheet offers an "Open
         * release page" action that navigates
         * to [releaseUrl].
         */
        data class UpdateAvailable(
            val currentVersion: String,
            val latestVersion: String,
            val releaseUrl: String,
        ) : UpdateInfo()

        /**
         * The check failed (network / rate
         * limit / parse). The Settings sheet
         * surfaces a non-blocking snackbar.
         */
        data class Unavailable(val reason: String) : UpdateInfo()
    }

    /**
     * Hit the GitHub Releases API and parse the
     * response. The release list is sorted by
     * `published_at` DESC; the first entry is
     * the latest release.
     *
     * The version comparison is a simple
     * numeric split on `.` (so "1.10.0" > "1.9.0"
     * because the segment is 10 > 9). A
     * pre-release tag (e.g. `v1.10.0-rc1`) is
     * treated as a release (the Settings sheet
     * surfaces the version string verbatim).
     */
    suspend fun check(): UpdateInfo = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$repoOwner/$repoName/releases"
        try {
            val response = httpClient.get(url)
            val text = response.bodyAsText()
            val array = JSONObject("{\"releases\":" + text + "}").getJSONArray("releases")
            if (array.length() == 0) {
                return@withContext UpdateInfo.Unavailable("no releases found")
            }
            val latest = array.getJSONObject(0)
            val latestTag = latest.optString("tag_name", "")
            val releaseUrl = latest.optString(
                "html_url",
                "https://github.com/$repoOwner/$repoName/releases",
            )
            if (latestTag.isBlank()) {
                return@withContext UpdateInfo.Unavailable("empty tag_name")
            }
            val currentVersion = BuildConfig.VERSION_NAME
            val comparison = compareVersions(currentVersion, stripTagPrefix(latestTag))
            when {
                comparison < 0 -> UpdateInfo.UpdateAvailable(
                    currentVersion = currentVersion,
                    latestVersion = stripTagPrefix(latestTag),
                    releaseUrl = releaseUrl,
                )
                else -> UpdateInfo.UpToDate(currentVersion = currentVersion)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            UpdateInfo.Unavailable("Could not check for updates.")
        }
    }

    private fun stripTagPrefix(tag: String): String =
        if (tag.startsWith("v") || tag.startsWith("V")) tag.substring(1) else tag

    /**
     * Compare two semantic versions. Returns
     * -1 if [a] < [b], 0 if equal, +1 if [a] >
     * [b]. A non-numeric segment (e.g. a
     * pre-release suffix) is treated as 0.
     */
    private fun compareVersions(a: String, b: String): Int {
        val aParts = a.split(".").map { it.toIntOrNull() ?: 0 }
        val bParts = b.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(aParts.size, bParts.size)
        for (i in 0 until maxLen) {
            val av = aParts.getOrElse(i) { 0 }
            val bv = bParts.getOrElse(i) { 0 }
            if (av != bv) return if (av < bv) -1 else 1
        }
        return 0
    }
}
