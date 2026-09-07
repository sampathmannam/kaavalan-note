package com.kaavalan.note.ui.util

import com.kaavalan.note.features.capture.ErrorType
import java.io.IOException

/**
 * v2.0.0 (drop Supabase): simplified error mapper. The
 * v1.x SafeError had HTTP-status mappings for
 * [io.github.jan.supabase.exceptions.RestException] and
 * [io.github.jan.supabase.auth.exception.AuthRestException].
 * With the cloud gone, those exception types are no longer
 * thrown. The mapper now handles:
 *  - [IOException] (and subclasses: `SQLiteException`,
 *    `SQLiteFullException`, `FileNotFoundException`,
 *    `SocketTimeoutException`, `UnknownHostException` — all
 *    are IOException subclasses) → "No connection. Check
 *    your network." or local-disk-flavor equivalent.
 *  - Anything else → the caller-supplied [default].
 *
 * The cloud-specific "Sign-in service unavailable" /
 * "Save service unavailable" / "Your session expired" branches
 * are gone. The user's local data cannot fail because of a
 * remote server, only because of a local disk / permission /
 * storage issue — and the message is consistent across all
 * of them: "Local storage error" + the default text.
 *
 * **What this is NOT.** It is not a general-purpose error
 * mapper. It maps throwables the Kaavalan note codebase actually
 * throws. Add new branches only when a new throwable class
 * becomes a real user-facing failure mode.
 */
object SafeError {

    /**
     * Map any throwable to a user-facing string. The default
     * is used when nothing more specific applies. We
     * deliberately do NOT include the exception message
     * (BEAU-NEW-01 invariant — never leak URLs, status codes,
     * stack traces, or SDK names).
     */
    fun forUser(e: Throwable, default: String): String = when {
        e is IOException -> "Local storage error. Check available space."
        else -> default
    }

    /**
     * v2.0.0: save-context variant. Same shape as [forUser]
     * but with a save-specific default.
     */
    fun forUserSave(e: Throwable, default: String): String = when {
        e is IOException -> "Local storage error. Check available space."
        else -> default
    }

    /**
     * v1.4 (PHONE-FINDING-7): user-facing text for the
     * [com.kaavalan.note.features.capture.CaptureUiState.errorType]
     * discriminator. It maps only real capture failures with a
     * stable user-facing message.
     */
    fun forCaptureErrorType(type: ErrorType): String? = when (type) {
        ErrorType.NONE -> null
        ErrorType.NETWORK_UNAVAILABLE ->
            "Local storage error. Check available space."
        ErrorType.PERMISSION_DENIED -> null
        ErrorType.UNKNOWN -> null
    }

    /**
     * v1.4 (PHONE-FINDING-7): classify a thrown exception into
     * the [ErrorType] the VM should set on the [CaptureUiState].
     * v2.0.0: simplified to local-only failures.
     */
    fun classifyForCapture(e: Throwable): ErrorType = when {
        e is IOException -> ErrorType.NETWORK_UNAVAILABLE
        else -> ErrorType.UNKNOWN
    }
}
