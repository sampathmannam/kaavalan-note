package com.kaavalan.note.ui.util

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v1.9.0 (PROD-READINESS-P3-P1-#1) + v2.1.1 (security):
 * the in-app crash log test. Verifies that
 * [CrashLog.write] creates a file in
 * `cacheDir/crashes/` with the expected structure,
 * that [CrashLog.mostRecent] returns the most recent
 * file, and that v2.1.1's PII redaction scrubs
 * email / phone / displayName from the rendered
 * log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrashLogTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        // Wipe any crash files from previous tests.
        CrashLog.clear(context)
    }

    @After
    fun tearDown() {
        CrashLog.clear(context)
    }

    @Test
    fun `write creates a file in cacheDir crashes with the expected header`() {
        val report = CrashLog.write(
            context,
            IllegalStateException("synthetic test crash"),
        )
        assertNotNull("CrashLog.write should return a report on success", report)
        assertTrue("crash file should exist", report!!.file.exists())
        val content = report.file.readText()
        // v2.1.0+ format: the rendered log has a
        // `# Kaavalan note crash log` header line.
        assertTrue(
            "crash file should start with the Kaavalan note header",
            content.startsWith("# Kaavalan note crash log"),
        )
        // Diagnostic type and frames remain, but messages can contain
        // note/contact text and therefore must never be persisted.
        assertTrue(
            "crash file should contain the exception type",
            content.contains("java.lang.IllegalStateException"),
        )
        assertTrue(
            "crash file should not contain the exception message",
            !content.contains("synthetic test crash"),
        )
    }

    @Test
    fun `mostRecent returns the most recent crash file`() {
        val first = CrashLog.write(context, RuntimeException("first"))
        // Sleep 10 ms so the second file has a
        // strictly later mtime.
        Thread.sleep(10)
        val second = CrashLog.write(context, RuntimeException("second"))
        val mostRecent = CrashLog.mostRecent(context)
        assertEquals(
            "mostRecent should return the second file (latest mtime)",
            second?.file,
            mostRecent,
        )
        assertNotNull("first should be non-null", first)
    }

    @Test
    fun `mostRecent returns null when no crash files exist`() {
        assertNull(CrashLog.mostRecent(context))
    }

    @Test
    fun `clear removes all crash files`() {
        CrashLog.write(context, RuntimeException("a"))
        CrashLog.write(context, RuntimeException("b"))
        CrashLog.clear(context)
        assertNull(CrashLog.mostRecent(context))
    }

    // ----- v2.1.1 (security): PII redaction -----

    @Test
    fun `redactPii scrubs email addresses from a rendered log`() {
        val input = """
            # Kaavalan note crash log
            timestamp=2026-08-24T20:00:00+05:30
            # Stack trace
            java.lang.IllegalStateException: failed to send invite to
              alice@example.com — see also bob+filter@subdomain.example.co
              at com.kaavalan.note.data.Person.<init>(Person.kt:42)
        """.trimIndent()
        val redacted = CrashLog.redactPii(input)
        assertTrue(
            "redacted log should not contain the first email",
            !redacted.contains("alice@example.com"),
        )
        assertTrue(
            "redacted log should not contain the second email",
            !redacted.contains("bob+filter@subdomain.example.co"),
        )
        assertTrue(
            "redacted log should contain a [REDACTED_EMAIL] marker",
            redacted.contains("[REDACTED_EMAIL]"),
        )
    }

    @Test
    fun `redactPii scrubs phone numbers from a rendered log`() {
        val input = """
            # Stack trace
            java.lang.IllegalStateException: contact +1 555-123-4567
              or (555) 987-6543 or +91 98765 43210 about the bug
        """.trimIndent()
        val redacted = CrashLog.redactPii(input)
        assertTrue(
            "redacted log should not contain the US phone",
            !redacted.contains("555-123-4567"),
        )
        assertTrue(
            "redacted log should not contain the parens phone",
            !redacted.contains("(555) 987-6543"),
        )
        assertTrue(
            "redacted log should not contain the IN phone",
            !redacted.contains("98765 43210"),
        )
        assertTrue(
            "redacted log should contain a [REDACTED_PHONE] marker",
            redacted.contains("[REDACTED_PHONE]"),
        )
    }

    @Test
    fun `redactPii scrubs Person displayName from a rendered log`() {
        val input = """
            # Stack trace
            com.kaavalan.note.data.captures.RoomCaptureRepository.create
              failed for Person(displayName="Alice Smith", phone="+1 555-0000")
        """.trimIndent()
        val redacted = CrashLog.redactPii(input)
        assertTrue(
            "redacted log should not contain the displayName value",
            !redacted.contains("Alice Smith"),
        )
        assertTrue(
            "redacted log should contain a redacted displayName marker",
            redacted.contains("displayName=[REDACTED_NAME]"),
        )
    }

    @Test
    fun `write actually persists the redacted log to disk`() {
        val ex = IllegalStateException(
            "failed to email alice@example.com about the +1 555-000-1234 " +
                "phone for Person(displayName=\"Alice\")",
        )
        val report = CrashLog.write(context, ex)!!
        val content = report.file.readText()
        // None of the PII tokens should be on disk.
        assertTrue(
            "persisted log should not contain the email",
            !content.contains("alice@example.com"),
        )
        assertTrue(
            "persisted log should not contain the phone",
            !content.contains("555-000-1234"),
        )
        assertTrue(
            "persisted log should not contain the displayName",
            !content.contains("\"Alice\""),
        )
    }

    @Test
    fun `write excludes messages from causes and suppressed exceptions`() {
        val cause = IllegalArgumentException("contact Alice secret note")
        val ex = IllegalStateException("top-level instruction text", cause).apply {
            addSuppressed(IllegalStateException("suppressed private value"))
        }

        val content = CrashLog.write(context, ex)!!.file.readText()

        assertTrue(content.contains("Caused by: java.lang.IllegalArgumentException"))
        assertTrue(content.contains("Suppressed: java.lang.IllegalStateException"))
        assertTrue(!content.contains("contact Alice secret note"))
        assertTrue(!content.contains("top-level instruction text"))
        assertTrue(!content.contains("suppressed private value"))
    }
}
