package com.kaavalan.note.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.Priority
import com.kaavalan.note.data.instructions.RoomInstructionRepository
import com.kaavalan.note.data.instructions.Source
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.PersonDao
import com.kaavalan.note.data.local.entities.PersonEntity
import com.kaavalan.note.data.person.PersonRepository
import com.kaavalan.note.data.reminder.ReminderManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * M3-T6: ViewModel for [PersonDetailScreen]. Reads the
 * `personId` from the nav arg (`SavedStateHandle`) and combines
 * the person row with the per-person instruction timeline.
 *
 * **Reactive.** Both the person row and the instructions are
 * observed as Flows so the screen updates on every local write
 * (e.g. `setSensitive`) AND on every Realtime-driven refresh.
 * The screen never has to call a `refresh()` itself. The same
 * Room-mirror contract as the HomeScreen applies.
 *
 * **v1.1.1 root-cause fix:** the person row was previously a
 * one-shot `getById` read in `init` — the `MutableStateFlow`
 * never re-emitted when the local row changed, so the detail
 * screen's "Mark as sensitive" button stayed stale after a
 * tap. We now use [PersonDao.observeById] which emits on every
 * Room update to the row.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class PersonDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    personDao: PersonDao,
    private val instructionDao: InstructionDao,
    private val roomInstructionRepository: RoomInstructionRepository,
    private val personRepository: PersonRepository,
    private val reminderManager: ReminderManager,
    vaultModeHolder: com.kaavalan.note.data.vault.VaultModeHolder = com.kaavalan.note.data.vault.VaultModeHolder(),
) : ViewModel() {

    /**
     * M3-T6: read the `personId` nav arg. The [PersonDetailScreen]
     * composable doesn't pass it explicitly; Hilt's
     * `SavedStateHandle` carries the nav-arg through the ViewModel
     * constructor.
     */
    private val personId: String = savedStateHandle.get<String>(ARG_PERSON_ID)
        ?: error("$ARG_PERSON_ID missing from nav args")

    private val _personState = personDao.observeById(personId).combine(vaultModeHolder.mode) { person, mode ->
        person?.takeIf { it.vaultMode == mode.storageKey }
    }

    /**
     * M3-T6: when the person is loaded, observe their instruction
     * timeline. flatMapLatest swaps the inner Flow when the
     * outer (person) changes — only one inner Flow is active at
     * a time, so we don't leak observers.
     */
    val state: StateFlow<PersonDetailUiState> = _personState
        .flatMapLatest { person ->
            if (person == null) {
                flowOf(PersonDetailUiState.Unavailable)
            } else {
                instructionDao.observeForPerson(person.id).combine(flowOf(person)) { ins, p ->
                    PersonDetailUiState.Loaded(
                        person = p.toDomain(),
                        instructions = ins.map { it.toDomain() },
                    )
                }
            }
        }
        .catch { emit(PersonDetailUiState.Unavailable) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = PersonDetailUiState.Loading,
        )

    private fun com.kaavalan.note.data.local.entities.PersonEntity.toDomain() =
        com.kaavalan.note.data.person.Person(
            id = id,
            name = name,
            designation = designation,
            station = station,
            phone = phone,
            updatedAt = updatedAt,
            isSensitive = isSensitive,
            tier = tier,
            cadenceOverrideDays = cadenceOverrideDays,
            lastInteractionAt = lastInteractionAt,
        )

    private fun com.kaavalan.note.data.local.entities.InstructionEntity.toDomain(): Instruction =
        Instruction(
            id = id,
            personId = personId,
            direction = runCatching { com.kaavalan.note.data.instructions.Direction.valueOf(direction) }
                .getOrDefault(com.kaavalan.note.data.instructions.Direction.OUTGOING),
            status = runCatching { com.kaavalan.note.data.instructions.Status.valueOf(status) }
                .getOrDefault(com.kaavalan.note.data.instructions.Status.OPEN),
            source = runCatching { com.kaavalan.note.data.instructions.Source.valueOf(source) }
                .getOrDefault(com.kaavalan.note.data.instructions.Source.TEXT),
            priority = runCatching { com.kaavalan.note.data.instructions.Priority.valueOf(priority) }
                .getOrDefault(com.kaavalan.note.data.instructions.Priority.NORMAL),
            title = title,
            rawText = rawText,
            dueAt = dueAt,
            capturedAt = capturedAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
            isSensitive = isSensitive,
            completedAt = completedAt,
            droppedReason = droppedReason,
            dueAtMs = dueAtMs,
        )

    /**
     * v1.1: state-transition handlers. Each one writes through the
     * Room repository (which also enqueues a PENDING_UPDATE for the
     * sync outbox) and Room re-emits the timeline Flow, so the UI
     * sees the change synchronously.
     */
    private val eventChannel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val messages = eventChannel.receiveAsFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun markDone(instructionId: String, onSuccess: () -> Unit = {}) = mutate(onSuccess) {
        roomInstructionRepository.markDone(instructionId)
        reminderManager.cancelDelivery(instructionId)
    }

    fun markDropped(instructionId: String, reason: String?, onSuccess: () -> Unit = {}) = mutate(onSuccess) {
        roomInstructionRepository.markDropped(instructionId, reason)
        reminderManager.cancelDelivery(instructionId)
    }

    fun reopen(instructionId: String, onSuccess: () -> Unit = {}) = mutate(onSuccess) {
        roomInstructionRepository.reopen(instructionId)
        val item = (state.value as? PersonDetailUiState.Loaded)?.instructions?.firstOrNull { it.id == instructionId }
        item?.dueAtMs?.takeIf { it > System.currentTimeMillis() }?.let { reminderManager.update(instructionId, it) }
    }

    fun updateReminder(id: String, at: Long?) = mutate {
        require(at == null || at > System.currentTimeMillis())
        reminderManager.update(id, at)
    }

    private fun mutate(onSuccess: () -> Unit = {}, work: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { work(); onSuccess() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { eventChannel.trySend("Could not save that change. Please try again.") }
            finally { _busy.value = false }
        }
    }

    fun setInstructionSensitive(instructionId: String, sensitive: Boolean) {
        viewModelScope.launch {
            runCatching { roomInstructionRepository.setSensitive(instructionId, sensitive) }
        }
    }

    fun setPersonSensitive(personId: String, sensitive: Boolean) {
        viewModelScope.launch {
            runCatching { personRepository.setSensitive(personId, sensitive) }
        }
    }

    /**
     * v1.5.3 (VAULT-003): the "Add instruction" CTA on Person
     * Detail. Pre-fills [personId] so the new instruction is
     * attributed to this person. Goes through the same
     * Room-backed [RoomInstructionRepository.create] that the
     * global capture sheet uses, so the row is in PENDING_INSERT
     * and the sync_queue is enqueued (a no-op in vault mode).
     *
     * The [text] is the raw user-typed note. We use the first 40
     * chars as the title (same truncation rule as the global
     * "Save as text" path) to keep the Today tab readable.
     */
    fun createInstructionForThisPerson(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            runCatching {
                val title = if (trimmed.length > 40) trimmed.take(40) + "…" else trimmed
                roomInstructionRepository.create(
                    personId = personId,
                    source = Source.TEXT,
                    priority = Priority.NORMAL,
                    title = title,
                    rawText = trimmed,
                    dueAt = null,
                )
            }
        }
    }

    companion object {
        const val ARG_PERSON_ID = "personId"
    }
}

sealed interface PersonDetailUiState {
    data object Loading : PersonDetailUiState
    data object Unavailable : PersonDetailUiState
    data class Loaded(
        val person: com.kaavalan.note.data.person.Person,
        val instructions: List<com.kaavalan.note.data.instructions.Instruction>,
    ) : PersonDetailUiState
}
