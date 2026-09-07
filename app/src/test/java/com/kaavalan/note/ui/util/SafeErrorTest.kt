package com.kaavalan.note.ui.util

import com.kaavalan.note.features.capture.ErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * v2.0.0 (drop Supabase): [SafeError] is local-only. The
 * v1.x HTTP-status-code mapper (RestException 401/403/422/429/5xx
 * → specific user-facing strings) is gone — the cloud is gone.
 * The v2.0 contract is much simpler:
 *
 *  - [SafeError.forUser] / [SafeError.forUserSave]: `IOException`
 *    (or any subclass — covers SQLiteException, SocketTimeout, etc.)
 *    → "Local storage error. Check available space." Anything else
 *    → the caller's `default` string.
 *  - [SafeError.classifyForCapture]: `IOException` → NETWORK_UNAVAILABLE,
 *    anything else → UNKNOWN.
 *  - [SafeError.forCaptureErrorType]: maps the small enum set to the
 *    locked user-facing strings. Returns `null` for the cases the
 *    VM owns the message for.
 *
 * **BEAU-NEW-01 invariant (still in force):** the returned string
 * must never contain the underlying throwable's `message`. The
 * "default fallback" tests in the v1.x file are kept here so the
 * invariant stays locked.
 */
class SafeErrorTest {

    @Test
    fun `default string is returned for unknown throwable`() {
        val msg = SafeError.forUser(IllegalStateException("boom"), "Custom default.")
        assertEquals("Custom default.", msg)
    }

    @Test
    fun `default string is returned for unknown throwable - never leaks e_message`() {
        // A throwable with a security-sensitive message must not
        // leak via the default branch. The v2.0 default branch
        // returns the caller's `default` verbatim — there is no
        // way for the raw `e.message` to reach the UI.
        val rawMsg = "leaky-secret-DB-path-/data/data/com.kaavalan.note/databases/kaavalan-note.db"
        val msg = SafeError.forUser(IllegalStateException(rawMsg), "Safe fallback.")
        assertEquals("Safe fallback.", msg)
        assert(!msg.contains(rawMsg, ignoreCase = true)) { "SafeError leaked message: $msg" }
    }

    @Test
    fun `IOException returns local-storage-error string (forUser)`() {
        val e = IOException("Connection timed out reaching server")
        val msg = SafeError.forUser(e, "ignored")
        assertEquals("Local storage error. Check available space.", msg)
    }

    @Test
    fun `IOException subclass also maps to local-storage-error string (forUser)`() {
        // SQLiteException is an IOException subclass — this is
        // the path that fires when the SQLCipher-encrypted Room
        // DB write fails (e.g. disk full).
        val e = java.io.FileNotFoundException("/data/data/com.kaavalan.note/databases/kaavalan-note.db")
        val msg = SafeError.forUser(e, "ignored")
        assertEquals("Local storage error. Check available space.", msg)
    }

    @Test
    fun `null throwable message still returns default (forUser)`() {
        val e = IllegalStateException() // null message
        val msg = SafeError.forUser(e, "Default works.")
        assertEquals("Default works.", msg)
    }

    // -----------------------------------------------------------------
    // v1.4 (PHONE-FINDING-7): save-context mapper. v2.0.0: same
    // shape, same IOException handling, same default fallback.
    // -----------------------------------------------------------------

    @Test
    fun `forUserSave returns local-storage-error string for IOException`() {
        val e = IOException("disk full")
        val msg = SafeError.forUserSave(e, "ignored")
        assertEquals("Local storage error. Check available space.", msg)
    }

    @Test
    fun `forUserSave returns default for unknown throwable`() {
        val msg = SafeError.forUserSave(IllegalStateException("boom"), "Save default.")
        assertEquals("Save default.", msg)
    }

    @Test
    fun `forUserSave never leaks e_message`() {
        val rawMsg = "leaky-secret-DB-path-/data/data/com.kaavalan.note/databases/kaavalan-note.db"
        val msg = SafeError.forUserSave(IllegalStateException(rawMsg), "Safe save fallback.")
        assertEquals("Safe save fallback.", msg)
        assert(!msg.contains(rawMsg, ignoreCase = true)) { "SafeError leaked message: $msg" }
    }

    @Test
    fun `forUserSave null throwable message still returns default`() {
        val e = IllegalStateException() // null message
        val msg = SafeError.forUserSave(e, "Save default works.")
        assertEquals("Save default works.", msg)
    }

    // -----------------------------------------------------------------
    // Capture-context mapper: only current failure types retain a
    // user-facing mapping. Setup state is not an error condition.
    // -----------------------------------------------------------------

    @Test
    fun `forCaptureErrorType - NONE returns null so the sheet renders nothing`() {
        assertNull(SafeError.forCaptureErrorType(ErrorType.NONE))
    }

    @Test
    fun `forCaptureErrorType - NETWORK_UNAVAILABLE returns the local-storage message`() {
        // v2.0.0: NETWORK_UNAVAILABLE is the IOException
        // bucket — the user sees the same message as the
        // forUser mapper. The v1.x convention was to return
        // null here and let the VM render; v2.0.0 inlines the
        // message because there's no separate "network
        // unavailable" condition (local disk-full is the only
        // thing that maps here).
        val s = SafeError.forCaptureErrorType(ErrorType.NETWORK_UNAVAILABLE)
        assertEquals("Local storage error. Check available space.", s)
    }

    @Test
    fun `forCaptureErrorType - PERMISSION_DENIED returns null (VM owns the message)`() {
        assertNull(SafeError.forCaptureErrorType(ErrorType.PERMISSION_DENIED))
    }

    @Test
    fun `forCaptureErrorType - UNKNOWN returns null (VM owns the message)`() {
        assertNull(SafeError.forCaptureErrorType(ErrorType.UNKNOWN))
    }

    @Test
    fun `classifyForCapture - IOException is NETWORK_UNAVAILABLE`() {
        assertEquals(ErrorType.NETWORK_UNAVAILABLE, SafeError.classifyForCapture(IOException("disk full")))
    }

    @Test
    fun `classifyForCapture - IOException subclass is NETWORK_UNAVAILABLE`() {
        // FileNotFoundException is an IOException subclass — the
        // path that fires when a SAF-chosen export URI is
        // revoked between the user picking it and the export
        // writing to it.
        val e: Throwable = java.io.FileNotFoundException("/no/such/dir/export.csv")
        assertEquals(ErrorType.NETWORK_UNAVAILABLE, SafeError.classifyForCapture(e))
    }

    @Test
    fun `classifyForCapture - any non-IOException is UNKNOWN`() {
        assertEquals(ErrorType.UNKNOWN, SafeError.classifyForCapture(IllegalStateException("boom")))
        assertEquals(ErrorType.UNKNOWN, SafeError.classifyForCapture(RuntimeException()))
        assertEquals(ErrorType.UNKNOWN, SafeError.classifyForCapture(NullPointerException()))
    }
}
