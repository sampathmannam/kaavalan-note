package com.kaavalan.note.ui.hierarchy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaavalan.note.data.instructions.AudienceRef
import com.kaavalan.note.data.instructions.DeliveryService
import com.kaavalan.note.data.instructions.InstructionRepository
import com.kaavalan.note.data.instructions.Priority
import com.kaavalan.note.data.instructions.RosterBuilder
import com.kaavalan.note.data.instructions.RosterPicker
import com.kaavalan.note.data.instructions.Source
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.data.person.PersonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DispatchViewModel @Inject constructor(
    private val instructionRepository: InstructionRepository,
    private val personRepository: PersonRepository,
    private val deliveryService: DeliveryService,
) : ViewModel() {

    data class State(
        val audience: AudienceRef? = null,
        val dueAtMs: Long? = null,
        val channels: Set<DeliveryService.Channel> = setOf(DeliveryService.Channel.SMS),
        val recipientCount: Int = 0,
        val lastResult: DeliveryService.Result? = null,
        val roster: RosterPicker = RosterPicker(emptyList(), emptyList()),
        val rosterReady: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // v2.x (adversarial-QA fix): one-shot info messages for
    // non-error user-facing feedback, mirroring
    // CaptureViewModel's `infoChannel` (see
    // features/capture/CaptureViewModel.kt). [toggleChannel]
    // refuses to let the selected-channel set go empty -- the
    // last checked chip can't be unchecked -- but that guard
    // used to be a silent no-op, indistinguishable from an
    // unresponsive control. It now emits a message here so
    // [DispatchSheet] can surface it as a Snackbar. The Channel
    // buffers the message so a config change between the tap and
    // the Snackbar collect doesn't drop it.
    internal val infoChannel: Channel<String> = Channel(capacity = Channel.BUFFERED)
    val infoMessages: Flow<String> = infoChannel.receiveAsFlow()

    init { viewModelScope.launch { refreshRoster() } }

    fun setAudience(a: AudienceRef?) {
        // The roster may still be loading. We accept the audience
        // pointer eagerly (so the picker can close and the chip
        // updates immediately), but the recipient count is recomputed
        // against the current `roster` — which may be the empty
        // default if `refreshRoster` hasn't emitted yet. Callers that
        // show a "loading" affordance should observe `rosterReady`
        // and disable the picker's confirm button until it flips true.
        _state.update { it.copy(audience = a, recipientCount = computeRecipients(a, it.roster)) }
    }
    // v2.x (adversarial-QA fix): DueChip's DatePickerDialog has no
    // minimum-date restriction, so a past date was fully selectable
    // and got rendered verbatim into the outgoing message via
    // HeaderTemplate.wrap ("Due: 2024-01-01 12:00" on a message sent
    // today), with none of the past-date handling
    // features/capture/CalendarGate.kt already has for the same
    // "date resolved to before now" case (CalendarGate returns
    // Skipped(SkipReason.IN_PAST) instead of honoring the stale
    // date). Mirror that here: refuse the update and surface why,
    // the same refuse-and-explain shape toggleChannel uses above for
    // its own guard.
    fun setDue(dueAtMs: Long?) {
        if (dueAtMs != null && dueAtMs < System.currentTimeMillis()) {
            infoChannel.trySend("That date is already past — pick a date in the future.")
            return
        }
        _state.update { it.copy(dueAtMs = dueAtMs) }
    }
    fun toggleChannel(channel: DeliveryService.Channel) {
        val current = _state.value.channels
        val next = if (channel in current) current - channel else current + channel
        if (next.isEmpty()) {
            // Refusing to let the set go empty is correct -- submit
            // requires at least one channel -- but doing so silently
            // is indistinguishable from a broken chip. Tell the user
            // why the tap didn't change anything, instead of
            // no-op-ing inside the state.update CAS loop (a side
            // effect there could fire more than once under
            // contention).
            infoChannel.trySend("At least one channel must stay selected.")
            return
        }
        _state.update { it.copy(channels = next) }
    }

    fun refreshRoster() {
        viewModelScope.launch {
            val people: List<Person> = personRepository.observeAll().first()
            val roster = RosterBuilder.build(people)
            _state.update { it.copy(roster = roster, recipientCount = computeRecipients(it.audience, roster), rosterReady = true) }
        }
    }

    fun submit(title: String, rawText: String, senderName: String, senderDesignation: String?, senderDivision: String?, onDone: (String) -> Unit) {
        val s = _state.value
        val audience = s.audience
        if (audience == null) {
            viewModelScope.launch {
                val ins = instructionRepository.create(personId = null, source = Source.TEXT, priority = Priority.NORMAL, title = title.ifBlank { rawText.take(60) }, rawText = rawText, dueAt = null)
                onDone(ins.id)
            }
            return
        }
        viewModelScope.launch {
            val ins = instructionRepository.createWithAudience(personId = null, audience = audience, source = Source.TEXT, priority = Priority.NORMAL, title = title.ifBlank { rawText.take(60) }, rawText = rawText, dueAt = null, dueAtMs = s.dueAtMs, channel = s.channels.joinToString(",") { it.name }.ifBlank { null })
            val request = DeliveryService.DeliveryRequest(instructionId = ins.id, title = ins.title, body = rawText, audience = audience, dueAtMs = s.dueAtMs, channels = s.channels, senderName = senderName, senderDesignation = senderDesignation, senderDivision = senderDivision)
            val result = deliveryService.dispatch(request, s.roster)
            instructionRepository.setChannel(id = ins.id, channel = s.channels.joinToString(",") { it.name }.ifBlank { null })
            _state.update { it.copy(lastResult = result) }
            // v2.x (adversarial-QA fix, product decision): a dispatch send
            // used to auto-mark the instruction DONE purely because the
            // SMS/WhatsApp intent launched -- conflating "message sent" with
            // "task completed by the recipient". The officer who dispatched
            // it decides when it's actually done, the same way every other
            // instruction in this app works (explicit "Mark done").
            onDone(ins.id)
        }
    }

    private fun computeRecipients(audience: AudienceRef?, roster: RosterPicker): Int {
        if (audience == null) return 0
        return when (audience) {
            is AudienceRef.ByPerson -> if (roster.allPeople.any { it.id == audience.personId }) 1 else 0
            is AudienceRef.ByDesignation -> roster.peopleByDesignation(audience.designation).size
            is AudienceRef.ByStation -> roster.stations.firstOrNull { it.station.equals(audience.station, ignoreCase = true) }?.totalPeople ?: 0
            is AudienceRef.ByAll -> roster.totalPeople
        }
    }
}
