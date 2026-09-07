package com.kaavalan.note.features.capture

import android.content.Context
import android.os.Bundle
import android.os.ResultReceiver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaavalan.note.data.captures.CaptureMode
import com.kaavalan.note.data.captures.CaptureRepository
import com.kaavalan.note.data.instructions.InstructionRepository
import com.kaavalan.note.data.instructions.MentionAndTagParser
import com.kaavalan.note.data.instructions.Priority
import com.kaavalan.note.data.instructions.Source
import com.kaavalan.note.data.person.PersonRepository
import com.kaavalan.note.data.tags.RoomTagRepository
import com.kaavalan.note.ui.util.SafeError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the note bar + capture sheet.
 *
 * v1.6.1: the on-device LLM (llama.cpp + whisper.cpp) is gone.
 * There is no extraction, no proposal, no confirmation card.
 * The capture flow is:
 *
 *   1. User taps the note bar — the sheet opens.
 *   2. User types, or speaks (`android.speech.SpeechRecognizer`),
 *      or photographs (CameraX + ML Kit on-device OCR).
 *   3. User taps Save — the note is persisted with the current
 *      [CaptureMode] (TEXT / VOICE / PHOTO), `personId = null`,
 *      `priority = NORMAL`, and the title is the first 40 chars
 *      of the note.
 *
 * The "Add to Calendar" toggle still works (M1-T6) — the
 * calendar event is fired on Save, not on Extract. The tag
 * picker is unchanged.
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val captureRepository: CaptureRepository,
    private val personRepository: PersonRepository,
    private val instructionRepository: InstructionRepository,
    private val tagRepository: RoomTagRepository,
) : ViewModel() {

    /**
     * v1.4 (F-09): initial state is read from [SavedStateHandle] so
     * a process death + relaunch restores the partial capture note
     * instead of silently losing it. We persist text / mode /
     * selectedTagIds via an [init] observer below; the Bundle only
     * holds plain types (String, Int, ArrayList<String>).
     */
    private val _state: MutableStateFlow<CaptureUiState> = MutableStateFlow(
        CaptureUiState(
            text = savedStateHandle.get<String>(KEY_TEXT) ?: "",
            mode = savedStateHandle.get<String>(KEY_MODE)
                ?.let { runCatching { CaptureMode.valueOf(it) }.getOrNull() }
                ?: CaptureMode.TEXT,
            selectedTagIds = (
                savedStateHandle.get<ArrayList<String>>(KEY_SELECTED_TAG_IDS)
                    ?: arrayListOf()
                ).toSet(),
        ),
    )
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    init {
        // v1.4 (F-09): auto-persist on every state change. This
        // hooks the in-memory state to the Bundle via the
        // SavedStateHandle that Hilt provides. The onSave success
        // path calls [clearDraft] to wipe; [dismissSheet] now
        // preserves the draft.
        viewModelScope.launch {
            _state.collect { current ->
                savedStateHandle[KEY_TEXT] = current.text
                savedStateHandle[KEY_MODE] = current.mode.name
                savedStateHandle[KEY_SELECTED_TAG_IDS] = ArrayList(current.selectedTagIds)
            }
        }
    }

    /**
     * v1.4 (F-09): explicit wipe of the in-flight draft. Called by
     * the Save success path ([onSaveRaw]). Unlike [dismissSheet],
     * this clears the SavedStateHandle-backed fields so a process
     * death + relaunch does not restore a stale draft.
     *
     * v1.8.0 (PROD-READINESS-P0-#2): the dedup fingerprint +
     * timestamp are NOT wiped here — they are the "did we
     * recently save this?" signal that survives a process death
     * and protects against a duplicate-save loop. The next save
     * with a different draft will not match the fingerprint and
     * will save normally.
     */
    fun clearDraft() {
        savedStateHandle.remove<String>(KEY_TEXT)
        savedStateHandle.remove<String>(KEY_MODE)
        savedStateHandle.remove<ArrayList<String>>(KEY_SELECTED_TAG_IDS)
        _state.value = CaptureUiState()
    }

    private companion object {
        const val KEY_TEXT = "capture.text"
        const val KEY_MODE = "capture.mode"
        const val KEY_SELECTED_TAG_IDS = "capture.selectedTagIds"
        // v1.8.0 (PROD-READINESS-P0-#2): fingerprint of the last
        // successful save (sha1 of text|mode|sortedTagIds). On a
        // process death + relaunch, the VM compares the current
        // draft's fingerprint to this value; a match means the
        // user is being asked to re-save the same note that was
        // already persisted. The dedup window is
        // [DEDUP_WINDOW_MS] (30s) — long enough to cover a
        // process death on a typical save path, short enough that
        // a user who genuinely wants to re-save the same note 5
        // minutes later can.
        const val KEY_LAST_SAVED_FINGERPRINT = "capture.lastSavedFingerprint"
        const val KEY_LAST_SAVED_AT_MS = "capture.lastSavedAtMs"
        const val DEDUP_WINDOW_MS = 30_000L
    }

    /**
     * v1.8.0 (PROD-READINESS-P0-#2): the dedup helper. Computes a
     * deterministic fingerprint of a draft (text + mode + sorted
     * tag IDs). Two drafts with the same fingerprint are
     * considered the same user intent.
     */
    private fun fingerprintOf(
        text: String,
        mode: CaptureMode,
        selectedTagIds: Set<String>,
    ): String {
        val sortedTags = selectedTagIds.sorted().joinToString(",")
        return "$text|$mode|$sortedTags".hashCode().toString()
    }

    /**
     * Roster presence remains useful for optional people-oriented
     * affordances, but it must never block raw capture. A note can
     * legitimately be free-floating (`personId = null`) and the
     * capture-first flow must work on a brand-new device.
     */
    val hasPeople: StateFlow<Boolean> = personRepository.observeAll()
        .map { it.isNotEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    /**
     * M1-T6: one-shot side effects. The ViewModel emits an
     * [CalendarEventData] here when the user saves with "Add to
     * Calendar" on. The Composable collects this and launches
     * via [android.content.Context.startActivity]. The [Channel]
     * buffers the event so a config change (rotation) doesn't
     * drop the intent.
     */
    internal val calendarIntentsChannel: Channel<CalendarEventData> = Channel(capacity = Channel.BUFFERED)
    val calendarIntents: Flow<CalendarEventData> = calendarIntentsChannel.receiveAsFlow()

    /**
     * v1.8.0 (PROD-READINESS-P0-#4): one-shot info messages for
     * non-error user-facing feedback (e.g. "That date is already
     * past — note saved without a calendar reminder."). Distinct
     * from the inline `error` on the state because the past-date
     * case is a successful save with a caveat, not a failure.
     * The Composable collects this and surfaces it as a Snackbar
     * / inline notice. The [Channel] buffers the message so a
     * config change (rotation) doesn't drop it.
     */
    internal val infoChannel: Channel<String> = Channel(capacity = Channel.BUFFERED)
    val infoMessages: Flow<String> = infoChannel.receiveAsFlow()

    init {
        // M3-T7: keep `availableTags` warm. The user picks from
        // the full taxonomy; the Room cache is updated on every
        // Realtime tags event from HomeViewModel.
        viewModelScope.launch {
            tagRepository.observeAll().collect { tags ->
                _state.update { it.copy(availableTags = tags) }
            }
        }
    }

    fun openSheet() {
        _state.update { it.copy(isVisible = true) }
    }

    /**
     * v1.4 (F-09): dismiss the sheet WITHOUT wiping the draft. The
     * X / scrim / BACK path must preserve the partial note so a
     * re-open restores it. Use [clearDraft] for the explicit
     * "wipe the draft" path (called from the Save success path).
     */
    fun dismissSheet() {
        _state.update { it.copy(isVisible = false) }
    }

    /**
     * M3-T7: toggle a tag chip in the capture sheet. The set
     * is in memory only until the user taps Save; on Save the
     * instruction row is created, then each tag in the set is
     * linked via `instruction_tags`. The PENDING_INSERT status
     * on a freshly-created free-form tag is preserved through
     * this path: the sync queue drains on the next work run.
     */
    fun onTagToggled(tagId: String) {
        _state.update {
            val current = it.selectedTagIds
            val next = if (current.contains(tagId)) current - tagId else current + tagId
            it.copy(selectedTagIds = next)
        }
    }

    /**
     * M3-T7: add a free-form `#tag` to the note. The user
     * can pre-emptively add a tag from the picker.
     */
    fun onAddFreeTag(name: String) {
        val clean = name.trim().trimStart('#').take(40)
        if (clean.isBlank()) return
        viewModelScope.launch {
            val tag = tagRepository.findOrCreateFree(clean) ?: return@launch
            _state.update {
                it.copy(selectedTagIds = it.selectedTagIds + tag.id)
            }
        }
    }

    fun onTextChanged(text: String) {
        _state.update {
            it.copy(
                text = text,
                mode = CaptureMode.TEXT,
                error = null,
                dispatchSuggestion = detectDispatchSuggestion(text),
            )
        }
    }

    /**
     * v2.x: scan the in-progress note for an audience-shaped
     * `@mention` -- a designation (`@si`), a station
     * (`@station:Subedari`), or the broadcast form (`@all`). Those
     * are the three [com.kaavalan.note.data.instructions.MentionAndTagParser.Mention.Prefix]
     * values that map onto a dispatch audience.
     *
     * `Prefix.NAME` is deliberately excluded: `@ramesh` in
     * "spoke to @ramesh about the seizure case" is ordinary prose,
     * and offering to broadcast every time someone types a
     * colleague's name would be noise. A single named person is
     * also already served by the existing per-person "Add
     * instruction for X" flow on Person Detail.
     *
     * Runs on every keystroke, which is fine: `parse` is a single
     * left-to-right scan over a short capture note.
     */
    private fun detectDispatchSuggestion(text: String): DispatchSuggestion? {
        val mention = MentionAndTagParser.parse(text).mentions.firstOrNull { m ->
            m.prefix == MentionAndTagParser.Mention.Prefix.DESIGNATION ||
                m.prefix == MentionAndTagParser.Mention.Prefix.STATION ||
                m.prefix == MentionAndTagParser.Mention.Prefix.ALL
        } ?: return null
        val label = when (mention.prefix) {
            MentionAndTagParser.Mention.Prefix.ALL -> "everyone"
            // `payload` for a STATION mention is the part after
            // "station:", already lowercased by the parser.
            else -> mention.payload
        }
        return DispatchSuggestion(label = label, rawMention = mention.raw)
    }

    fun onAddToCalendarChanged(checked: Boolean) {
        _state.update { it.copy(addToCalendar = checked) }
    }

    /**
     * M2-T2: the camera returned an image. OCR it via ML Kit,
     * drop the recognised text into the capture sheet's text
     * field, open the sheet, and let the user tap Save. With
     * the v1.6.1 LLM drop there is no automatic extraction
     * step — the user reads the text and saves it as a
     * `CaptureMode.PHOTO` note.
     */
    fun onPhotoTextRecognized(text: String) {
        if (text.isBlank()) return
        _state.update { it.copy(text = text, mode = CaptureMode.PHOTO, error = null) }
        if (!_state.value.isVisible) {
            _state.update { it.copy(isVisible = true) }
        }
    }

    /**
     * v1.5.4: surface a photo-capture error (e.g. CAMERA perm
     * denied, OCR threw) as an inline message on the capture
     * sheet. Used by the HomeScreen's camera-permission flow
     * when the user declines the runtime perm.
     */
    fun onPhotoError(message: String) {
        _state.update { it.copy(error = "Photo: $message", isVisible = true) }
    }

    /**
     * Start the voice-capture flow. v1.6.1: voice transcription
     * is via the system `android.speech.SpeechRecognizer`
     * service (no LLM). The service posts the partial / final
     * transcript back through the [ResultReceiver]; we drop the
     * text into the capture sheet's field.
     */
    fun onVoiceStart(context: Context) {
        val receiver = object : ResultReceiver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                when (resultCode) {
                    VoiceCaptureService.RESULT_OK -> {
                        val text = resultData?.getString(VoiceCaptureService.KEY_TEXT) ?: ""
                        onVoiceTranscript(text)
                    }
                    VoiceCaptureService.RESULT_ERROR -> {
                        val err = resultData?.getString(VoiceCaptureService.KEY_ERROR) ?: "Unknown"
                        onVoiceError(err)
                    }
                }
            }
        }
        VoiceCaptureService.start(context, receiver)
    }

    /**
     * Cancel the voice-capture flow. The user can also let it
     * complete naturally; this is the cancel path.
     */
    fun onVoiceStop(context: Context) {
        VoiceCaptureService.stop(context)
    }

    /**
     * A transcript came back from the service. Pre-fill the
     * capture sheet's text and open it. The user then taps
     * Save and the regular flow takes over.
     */
    fun onVoiceTranscript(text: String) {
        if (text.isBlank()) return
        _state.update { it.copy(text = text, mode = CaptureMode.VOICE, error = null, isVisible = true) }
    }

    /**
     * An error came back from the voice service. Surface it
     * inline on the capture sheet.
     */
    fun onVoiceError(message: String) {
        _state.update { it.copy(error = "Voice capture failed: $message") }
    }

    /**
     * v1.6.1: the only save path. The user types (or
     * voice-transcribes, or photo-OCR's) a note, taps Save,
     * and the note is persisted. The instruction lands with:
     *   - `personId = null` (free-floating; a future
     *     "link to person" flow can attach one).
     *   - `source = TEXT | VOICE | PHOTO` (preserved).
     *   - `priority = NORMAL`.
     *   - `title = rawText.take(40)` with a trailing `…` if
     *     truncated.
     *   - `dueAt = null` (the user can set a due date in the
     *     instruction row's edit sheet, not the capture flow).
     *
     * If the user has selected tags in the tag picker, the
     * tag links are attached in the same success path.
     *
     * The "Add to Calendar" toggle fires a calendar intent
     * with the first 40 chars as the title and the full
     * text as the description.
     */
    fun onSaveRaw() {
        val current = _state.value
        if (!current.canSaveRaw) return
        // Free-floating capture is intentional. A person link is
        // optional context, not a prerequisite for retaining an
        // instruction that might otherwise be lost.
        // v1.8.0 (PROD-READINESS-P0-#2): the crash-recovery
        // dedup guard. If a previous save of the same draft
        // (text + mode + tags) completed within the dedup
        // window, this is the user being asked to re-save
        // after a process death between [create] and
        // [clearDraft]. Surface a one-shot info message and
        // clear the draft so the user isn't stuck in a loop.
        val currentFingerprint = fingerprintOf(
            text = current.text,
            mode = current.mode,
            selectedTagIds = current.selectedTagIds,
        )
        val lastFingerprint = savedStateHandle.get<String>(KEY_LAST_SAVED_FINGERPRINT)
        val lastSavedAtMs = savedStateHandle.get<Long>(KEY_LAST_SAVED_AT_MS) ?: 0L
        val nowMs = System.currentTimeMillis()
        if (lastFingerprint == currentFingerprint && lastSavedAtMs > 0L && (nowMs - lastSavedAtMs) < DEDUP_WINDOW_MS) {
            infoChannel.trySend(
                "Already saved before the app closed — clearing the draft."
            )
            clearDraft()
            return
        }
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val rawText = current.text.trim()
            val truncated = rawText.take(40)
            val title = if (rawText.length > 40) "$truncated…" else rawText
            val result = runCatching {
                instructionRepository.create(
                    personId = null,
                    source = modeToSource(current.mode),
                    priority = Priority.NORMAL,
                    title = title,
                    rawText = rawText,
                    dueAt = null,
                )
            }
            result.onSuccess { created ->
                // v1.6.1: insert a captures row so the audit
                // trail preserves the capture source. The
                // captures table is the "raw" record; the
                // instructions table is the saved note.
                runCatching {
                    captureRepository.create(rawText = rawText, mode = current.mode)
                }
                if (current.addToCalendar) {
                    val result = CalendarGate.buildEventData(
                        title = title,
                        description = rawText,
                        dueAt = null,
                    )
                    when (result) {
                        is CalendarEventResult.Event -> {
                            calendarIntentsChannel.trySend(result.data)
                        }
                        is CalendarEventResult.Skipped -> {
                            // v1.8.0 (PROD-READINESS-P0-#4): the only
                            // user-actionable skip reason is IN_PAST
                            // (the date the LLM or future Edit flow
                            // supplied resolved to a past timestamp).
                            // The note IS saved; the calendar event
                            // was not. Tell the user so they don't
                            // wonder why their calendar is empty.
                            if (result.reason == SkipReason.IN_PAST) {
                                infoChannel.trySend(
                                    "That date is already past — note saved without a calendar reminder."
                                )
                            }
                            // The v1.6.1 fall-throughs (MISSING /
                            // UNPARSEABLE) were collapsed into a
                            // single Skipped variant for the v1.8.0
                            // contract; the VM does not surface
                            // them because the event still fires
                            // (begin = now).
                        }
                    }
                }
                if (current.selectedTagIds.isNotEmpty()) {
                    runCatching {
                        tagRepository.attachToInstruction(
                            instructionId = created.id,
                            tagIds = current.selectedTagIds.toList(),
                        )
                    }
                }
                // v1.8.0 (PROD-READINESS-P0-#2): record the
                // dedup fingerprint + timestamp BEFORE clearing
                // the draft. The next onSaveRaw (or a process-death
                // + relaunch) consults these to detect a
                // duplicate-save attempt. The clearDraft() call
                // below also wipes these keys, so a fresh
                // start-of-session has no last-saved state.
                savedStateHandle[KEY_LAST_SAVED_FINGERPRINT] = currentFingerprint
                savedStateHandle[KEY_LAST_SAVED_AT_MS] = System.currentTimeMillis()
                // v1.4 (F-09): success path wipes the in-flight draft.
                clearDraft()
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        isSaving = false,
                        error = SafeError.forUserSave(
                            e = e,
                            default = "Could not save note.",
                        ),
                        errorType = SafeError.classifyForCapture(e),
                    )
                }
            }
        }
    }

    private fun modeToSource(mode: CaptureMode): Source = when (mode) {
        CaptureMode.TEXT -> Source.TEXT
        CaptureMode.VOICE -> Source.VOICE
        CaptureMode.PHOTO -> Source.PHOTO
    }
}
