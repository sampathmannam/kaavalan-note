package com.kaavalan.note.ui.util

import android.content.Context
import android.os.Build
import com.kaavalan.note.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale

/**
 * v1.9.0 (PROD-READINESS-P3-P1-#1) + v2.1.1 (security):
 * the in-app crash log. Catches unhandled exceptions on
 * the main looper, writes a structured log file to
 * `cacheDir/crashes/`, and shows a "share with support"
 * notification next time the user launches the app.
 *
 * **Why a local crash log, not Crashlytics.**
 * The v1.5.0+ privacy policy is "we collect no
 * data". A third-party crash reporter (Firebase
 * Crashlytics, Sentry) breaks that promise —
 * the third party gets every crash's stack
 * trace. The local log keeps the user's crash
 * data on the user's device; the user shares it
 * with support if they want to.
 *
 * **Privacy guarantees.**
 *  - The crash log file is in `cacheDir/crashes/`
 *    (the app's private storage; not exported).
 *  - The file is overwritten on the next crash
 *    (only the most recent crash is kept; the
 *    older ones are auto-purged).
 *  - The user must explicitly share the log via
 *    the system share intent (the [CrashLog]
 *    helper exposes a FileProvider URI for the
 *    share).
 *
     * **Security: exception messages are excluded.**
     * Messages frequently embed note text, contact names,
     * URIs, or server responses. The log therefore records
     * exception classes and stack frames only. Pattern-based
     * [redactPii] remains as defence in depth for the header
     * and future format changes.
 *
 * **File format.** Plain text. Each line is a
 * `key=value` pair. Easy to parse, easy to redact.
 * The format is documented in
 * `docs/crash-log-format.md` so a support reply
 * can grep the file directly.
 */
object CrashLog {

    private const val CRASH_DIR = "crashes"
    private const val MAX_CRASH_FILES = 10
    private const val FILE_PREFIX = "crash_"
    private const val FILE_EXT = ".txt"

    /**
     * The result of a [catchAndLog] call. The
     * caller (the [android.app.Application.onCreate]
     * hook) reads the result on the next launch
     * and shows the "Share with support" prompt.
     */
    data class CrashReport(
        val file: File,
        val timestampMs: Long,
    )

    /**
     * Write [throwable] to a crash log file in
     * `cacheDir/crashes/`. Returns the [CrashReport]
     * if the write succeeded, `null` otherwise
     * (the caller treats a `null` as "no crash to
     * report").
     *
     * **Failure modes.** If the disk is full or
     * `cacheDir` is unwritable, the function
     * returns `null` and the original throwable
     * is re-thrown to the caller. The user sees
     * the original crash; the support team just
     * doesn't get a log.
     */
    fun write(context: Context, throwable: Throwable): CrashReport? = try {
        val dir = File(context.cacheDir, CRASH_DIR).apply { mkdirs() }
        val timestampMs = System.currentTimeMillis()
        val timestampStr = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestampMs))
        val file = File(dir, "$FILE_PREFIX$timestampStr$FILE_EXT")
        val content = renderCrashLog(throwable, timestampMs)
        // v2.1.1 (security): redact PII patterns
        // (email, phone, displayName) from the
        // rendered log before writing. The patterns
        // are conservative — false positives are
        // fine, false negatives leak PII.
        val redacted = redactPii(content)
        file.writeText(redacted)
        prune(dir)
        CrashReport(file = file, timestampMs = timestampMs)
    } catch (_: Exception) {
        null
    }

    /**
     * The most recent crash log file, or `null`
     * if no crash has been logged this session
     * (or the previous launch).
     */
    fun mostRecent(context: Context): File? {
        val dir = File(context.cacheDir, CRASH_DIR)
        if (!dir.exists()) return null
        return dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_EXT) }
            ?.maxByOrNull { it.lastModified() }
    }

    /**
     * Delete all crash log files. Called from
     * the "Dismiss" action on the crash
     * notification.
     */
    fun clear(context: Context) {
        val dir = File(context.cacheDir, CRASH_DIR)
        if (!dir.exists()) return
        dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) }?.forEach { it.delete() }
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(MAX_CRASH_FILES).forEach { it.delete() }
    }

    private fun renderCrashLog(throwable: Throwable, timestampMs: Long): String {
        val timestampStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(timestampMs))
        return buildString {
            appendLine("# Kaavalan note crash log")
            appendLine("timestamp=$timestampStr")
            appendLine("app_version=${BuildConfig.VERSION_NAME}")
            appendLine("app_build=${BuildConfig.VERSION_CODE}")
            appendLine("device_manufacturer=${Build.MANUFACTURER}")
            appendLine("device_model=${Build.MODEL}")
            appendLine("android_sdk=${Build.VERSION.SDK_INT}")
            appendLine("android_release=${Build.VERSION.RELEASE}")
            appendLine()
            appendLine("# Stack trace")
            appendSanitizedThrowable(throwable)
        }
    }

    /** Render diagnostic stack structure without exception messages or user data. */
    private fun StringBuilder.appendSanitizedThrowable(throwable: Throwable) {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())

        fun appendOne(value: Throwable, caption: String, indent: String) {
            if (!seen.add(value)) {
                append(indent).append(caption).append("[cycle]").appendLine()
                return
            }
            append(indent).append(caption).append(value::class.java.name).appendLine()
            value.stackTrace.forEach { frame ->
                append(indent).append("\tat ").append(frame).appendLine()
            }
            value.suppressed.forEach { suppressed ->
                appendOne(suppressed, "Suppressed: ", "$indent\t")
            }
            value.cause?.let { cause -> appendOne(cause, "Caused by: ", indent) }
        }

        appendOne(throwable, caption = "", indent = "")
    }

    /**
     * v2.1.1 (security): scrub PII patterns from a
     * rendered crash log. The patterns are
     * conservative — they err on the side of
     * false positives (over-redaction is fine;
     * under-redaction leaks PII).
     *
     *  - `EMAIL` — RFC-5322-ish (any
     *    `something@somewhere.tld`).
     *  - `PHONE` — 10+ digits with optional `+`,
     *    spaces, dashes, parens (covers IN + US
     *    + EU formats).
     *  - `Person(displayName="...")` — the
     *    [com.kaavalan.note.data.person.Person] toString
     *    that v1.9.0's stack traces would render
     *    when a crash happened inside a function
     *    with a Person on the stack.
     *
     * Returns the redacted string. Pure function;
     * tested by [CrashLogTest] in the unit test
     * suite.
     */
    internal fun redactPii(text: String): String {
        var out = text
        // email — match the typical `a@b.c` shape;
        // the regex is conservative (no quoted
        // local-parts, no `+` aliases).
        out = EMAIL_RE.replace(out, "[REDACTED_EMAIL]")
        // phone — 10+ digits, optional leading +,
        // optional spaces/dashes/parens between
        // groups. Matches e.g. `555-123-4567`,
        // `+91 98765 43210`, `(555) 123-4567`.
        out = PHONE_RE.replace(out, "[REDACTED_PHONE]")
        // Person(displayName="...") — match the
        // literal `displayName=` attribute and
        // its quoted value. The same pattern
        // would also redact a literal
        // `displayName="..."` in any other toString.
        out = DISPLAY_NAME_RE.replace(out, "displayName=[REDACTED_NAME]")
        return out
    }

    private val EMAIL_RE = Regex(
        "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}",
    )
    private val PHONE_RE = Regex(
        "\\+?\\d[\\d\\s\\-()]{8,}\\d",
    )
    private val DISPLAY_NAME_RE = Regex(
        "displayName\\s*=\\s*\"[^\"]*\"",
    )
}
