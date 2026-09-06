package com.kaavalan.note.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.2.1: the release script's signing-key guard has to have something
 * to compare against.
 *
 * `release.sh` gate 6 reads the built APK's certificate fingerprint and
 * checks it against a pinned value. If the pin file is missing it takes
 * the other branch -- writes the fingerprint it just read, prints "This
 * is the FIRST release", and publishes. That branch cannot fail, so with
 * no pin file on disk the gate is decoration: every run re-pins to
 * whatever key happened to sign that build.
 *
 * The file was never committed. `release/` did not exist in the repo at
 * all, through every release up to v2.2.0, even though the script's own
 * banner says "commit it".
 *
 * What that costs is not a failed build. Android refuses an update
 * signed by a different key than the installed app, so publishing one
 * strands every existing user: their only way forward is uninstall and
 * reinstall, and for a local-only app uninstall is the data. Obtainium
 * users get an install error and nothing else. The guard exists to make
 * that impossible to do by accident, and it was not armed.
 *
 * The pinned value is the one `release.sh` itself printed into the
 * v2.2.0 release notes, which it takes from the same `$FINGERPRINT`
 * variable it compares. If it is ever wrong the script refuses to
 * publish rather than publishing the wrong thing, which is the safe
 * direction to be wrong in.
 */
class SigningFingerprintPinTest {

    /** The path is read from `release.sh` so the two cannot drift apart. */
    private val pinPath: String =
        Regex("FINGERPRINT_FILE=\"([^\"]+)\"")
            .find(File("../release.sh").readText(Charsets.UTF_8))
            ?.groupValues
            ?.get(1)
            ?: error("release.sh no longer defines FINGERPRINT_FILE")

    @Test
    fun `the pinned signing fingerprint is committed`() {
        val pin = File("../$pinPath")
        assertTrue(
            "release.sh pins the signing key against $pinPath. Without that file the " +
                "gate silently re-pins on every run and a keystore swap would publish " +
                "an update no existing install can accept",
            pin.exists(),
        )
    }

    @Test
    fun `the pinned fingerprint is a SHA-256 apksigner prints`() {
        val pin = File("../$pinPath").readText(Charsets.UTF_8).trim()
        assertEquals(
            "apksigner prints the SHA-256 digest as 64 lowercase hex characters with " +
                "no separators; release.sh compares it verbatim after stripping " +
                "whitespace, so anything else can never match",
            64,
            pin.length,
        )
        assertTrue(
            "pinned value must be lowercase hex, was: $pin",
            pin.all { it in "0123456789abcdef" },
        )
    }
}
