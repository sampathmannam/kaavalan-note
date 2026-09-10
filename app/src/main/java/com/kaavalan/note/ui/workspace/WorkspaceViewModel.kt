package com.kaavalan.note.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.RoomInstructionRepository
import com.kaavalan.note.data.instructions.toDomain
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.RoomPersonRepository
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.data.reminder.ReminderManager
import com.kaavalan.note.data.vault.VaultMode
import com.kaavalan.note.data.vault.VaultModeHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorkspaceState(
    val loading: Boolean = true,
    val instructions: List<Instruction> = emptyList(),
    val contacts: List<Person> = emptyList(),
    val error: String? = null,
    val hidden: Boolean = false,
)

/** One reactive read model for all three destinations; Room remains the source of truth. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    instructionDao: InstructionDao,
    private val people: RoomPersonRepository,
    private val instructions: RoomInstructionRepository,
    private val reminders: ReminderManager,
    vault: VaultModeHolder,
    val contactSyncService: com.kaavalan.note.data.person.ContactSyncService,
    userDao: com.kaavalan.note.data.user.UserDao,
    private val workflow: com.kaavalan.note.data.instructions.InstructionWorkflow,
) : ViewModel() {
    val deviceOwnerName = userDao.observeDeviceOwner().map { it?.displayName.orEmpty() }
        .catch { emit("") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    private val reload = MutableStateFlow(0)
    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()
    private val completions = Channel<com.kaavalan.note.data.instructions.CompletionUndo>(Channel.BUFFERED)
    val completed = completions.receiveAsFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    val state = combine(vault.mode, reload) { mode, _ -> mode }.flatMapLatest { mode ->
        combine(people.observeAllInMode(mode.storageKey), instructionDao.observeAll()) { contacts, rows ->
            WorkspaceState(
                loading = false,
                instructions = visibleWork(rows.map { it.toDomain() }, contacts, mode == VaultMode.Visible),
                contacts = contacts.sortedBy { it.name.lowercase() },
                hidden = mode == VaultMode.Hidden,
            )
        }.onStart { emit(WorkspaceState(hidden = mode == VaultMode.Hidden)) }
            .catch { failure ->
                // Record the failure type, never note contents or database/key material.
                android.util.Log.w("OfficerWorkspace", "Workspace read failed: ${failure.javaClass.simpleName}")
                emit(WorkspaceState(loading = false, hidden = mode == VaultMode.Hidden,
                    error = "Could not load your workspace. Your saved notes have not been changed."))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkspaceState())

    fun retry() { reload.value++ }

    fun complete(id: String, onSuccess: () -> Unit) = mutate(null, onSuccess) {
        requireVisible(id)
        completions.send(workflow.complete(id))
    }

    fun undoComplete(undo: com.kaavalan.note.data.instructions.CompletionUndo) = mutate("Completion undone") {
        workflow.undoCompletion(undo)
    }

    fun edit(id: String, text: String, direction: com.kaavalan.note.data.instructions.Direction, personId: String?, deadline: Long?, onSuccess: () -> Unit) =
        mutate("Instruction saved", onSuccess) { workflow.edit(id, text, direction, personId, deadline) }

    fun addUpdate(id: String, text: String, status: com.kaavalan.note.data.instructions.Status, followUp: Long?, onSuccess: () -> Unit) =
        mutate("Update saved", onSuccess) { workflow.addUpdate(id, text, status, followUp) }

    fun editContact(id: String, name: String, rank: String, station: String, phone: String, onSuccess: () -> Unit) =
        mutate("Contact saved", onSuccess) { workflow.editContact(id, name, rank, station, phone) }

    fun close(id: String, onSuccess: () -> Unit) = mutate("Closed without action", onSuccess) {
        requireVisible(id)
        workflow.close(id)
    }

    fun reopen(id: String, onSuccess: () -> Unit) = mutate("Instruction reopened", onSuccess) {
        requireVisible(id)
        workflow.reopen(id)
    }

    fun remind(id: String, at: Long?) = mutate(if (at == null) "Reminder removed" else "Reminder updated") {
        requireVisible(id)
        require(at == null || at > System.currentTimeMillis())
        workflow.changeReminder(id, at)
    }

    fun addContact(name: String, designation: String?, station: String?, onSuccess: () -> Unit) = mutate("Contact added", onSuccess) {
        require(name.isNotBlank())
        people.createContact(name.trim(), designation, station, vaultMode = if (state.value.hidden) "hidden" else "visible")
    }

    fun importContact(name: String, phone: String, onSuccess: () -> Unit) = mutate("Contact imported", onSuccess) {
        people.createContact(name.trim(), null, null, phone.trim().ifBlank { null }, if (state.value.hidden) "hidden" else "visible")
    }

    private fun requireVisible(id: String): Instruction = state.value.instructions.first { it.id == id }

    private fun mutate(message: String?, onSuccess: () -> Unit = {}, work: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                work()
                onSuccess()
                message?.let { events.trySend(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                events.trySend("Could not finish that action. Please try again; your record is still available.")
            } finally {
                _busy.value = false
            }
        }
    }
}
