package com.kaavalan.note.features.capture

import com.kaavalan.note.data.captures.CaptureMode
import com.kaavalan.note.data.tags.Tag

/**
 * State of the note bar / capture sheet.
 *
 * v1.6.1: the on-device LLM is gone. There is no
 * `proposal`, no `isExtracting`, no `canExtract`, no
 * `canConfirm`. The capture flow is:
 *
 *   type / voice / photo -> text in the field -> tap Save
 *   -> the note is persisted with `mode` reflecting the
 *   capture source, `personId = null`, and `priority = NORMAL`.
 *
 * The `addToCalendar` flag is kept because the user can
 * still attach a calendar event to a free-form note (the
 * M1-T6 calendar intent fires from the Save path, not the
 * Extract path).
 *
 * v1.4 (PHONE-FINDING-7): [error] travels with [errorType],
 * a discriminator that the UI uses to pick the right
 * colour + icon. The previous rendering was bright
 * `colorScheme.error` (red) with the generic message
 * "Could not save note. Try again." — both spec §1
 * violations.
 */
data class CaptureUiState(
    val isVisible: Boolean = false,
    val text: String = "",
    val mode: CaptureMode = CaptureMode.TEXT,
    val isSaving: Boolean = false,
    val addToCalendar: Boolean = false,
    val error: String? = null,
    val errorType: ErrorType = ErrorType.NONE,
    val availableTags: List<Tag> = emptyList(),
    val selectedTagIds: Set<String> = emptySet(),
) {
    val canSaveRaw: Boolean
        get() = isVisible && text.isNotBlank() && !isSaving
}

/**
 * v1.4 (PHONE-FINDING-7): the discriminator for the capture sheet
 * error.
 */
enum class ErrorType {
    NONE,
    NETWORK_UNAVAILABLE,
    PERMISSION_DENIED,
    UNKNOWN,
}
