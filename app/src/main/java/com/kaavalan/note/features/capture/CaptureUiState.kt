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
    val reminderAtMs: Long? = null,
    val direction: com.kaavalan.note.data.instructions.Direction = com.kaavalan.note.data.instructions.Direction.SELF,
    val personId: String? = null,
    val requiresContact: Boolean = false,
    val workspaceReady: Boolean = true,
    val error: String? = null,
    val errorType: ErrorType = ErrorType.NONE,
    val availableTags: List<Tag> = emptyList(),
    val selectedTagIds: Set<String> = emptySet(),
    // v2.x: set when the typed text contains an audience-shaped
    // @mention (a designation, a station, or @all). See
    // [DispatchSuggestion] -- this only OFFERS the dispatch flow, it
    // never switches surfaces on its own.
    val dispatchSuggestion: DispatchSuggestion? = null,
    // v2.6.0 (subdivision CRM): the work context this capture was started in — a matter
    // and/or station chosen before typing. Persisted in SavedStateHandle alongside the
    // draft, included in the duplicate-save fingerprint, applied atomically with the
    // insert, and cleared only after a durable save.
    val contextStationId: String? = null,
    val contextMatterId: String? = null,
    val contextLabel: String? = null,
    // Set when a new context arrives while a non-empty draft is already in flight. The
    // draft is kept and the officer is offered a clearly labelled choice; nothing is
    // silently replaced.
    val pendingContext: PendingCaptureContext? = null,
) {
    val canSaveRaw: Boolean
        get() = isVisible && text.isNotBlank() && !isSaving && workspaceReady && (!requiresContact || personId != null)

    val hasContext: Boolean get() = contextStationId != null || contextMatterId != null
}

/**
 * A work context offered to an already-started draft. [label] is what the officer sees, so
 * the choice names both sides rather than asking about an abstract "context".
 */
data class PendingCaptureContext(
    val stationId: String?,
    val matterId: String?,
    val label: String,
)


/**
 * v2.x (product decision, adversarial-QA follow-up): the hierarchy
 * dispatch flow (`ui/hierarchy/DispatchComposerSheet`) shipped in
 * v2.1.1 with zero live entry points -- nothing in the app ever
 * opened it, and [com.kaavalan.note.data.instructions.MentionAndTagParser]
 * was dead code for the same reason. The decided entry point is the
 * note bar itself: typing an audience-shaped `@mention` (e.g. `@si`,
 * `@station:Subedari`, `@all`) offers to turn the note into a
 * dispatch, which keeps the app's "one primary input" rule intact
 * rather than adding a second compose button.
 *
 * Deliberately an OFFER, not a hijack. "spoke to @si about the
 * seizure case" is an ordinary note, and silently swapping the
 * capture sheet for the dispatch composer mid-sentence would be the
 * same class of surprise as the v2.1.2 NoteBar bug (a tap doing
 * something the user did not ask for). The user taps the suggestion
 * to switch; ignoring it saves a normal note.
 *
 * [label] is the human-readable audience ("SI", "Subedari",
 * "everyone") for the suggestion row's copy.
 */
data class DispatchSuggestion(
    val label: String,
    val rawMention: String,
)

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
