package com.kaavalan.note.ui.subdivision

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.toDomain
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.RoomPersonRepository
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.data.subdivision.Matter
import com.kaavalan.note.data.subdivision.Station
import com.kaavalan.note.data.subdivision.SubdivisionDao
import com.kaavalan.note.data.subdivision.SubdivisionProfile
import com.kaavalan.note.data.subdivision.SubdivisionProjections
import com.kaavalan.note.data.subdivision.SubdivisionRepository
import com.kaavalan.note.data.subdivision.SubdivisionReview
import com.kaavalan.note.data.subdivision.StaffPosting
import com.kaavalan.note.data.vault.VaultMode
import com.kaavalan.note.data.vault.VaultModeHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One lifecycle-aware read model for every subdivision destination, and the only place
 * the UI calls a subdivision mutation from. No Composable touches a DAO.
 *
 * **Vault changes reset before they reveal.** The whole pipeline hangs off
 * `flatMapLatest(vault.mode)`, so switching workspace cancels the previous collection and
 * the new branch emits a fresh loading (or unavailable) state before any row arrives. The
 * officer never sees the previous workspace's records for a frame.
 *
 * **Read errors are recoverable, mutation errors are actionable.** A failed read shows a
 * retry affordance and says the saved records were not changed. A refused mutation
 * surfaces the repository's own sentence, which is written to tell the officer what to do
 * next ("Move this station's staff first"), not to describe a stack.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SubdivisionViewModel @Inject constructor(
    private val repository: SubdivisionRepository,
    subdivisionDao: SubdivisionDao,
    people: RoomPersonRepository,
    instructionDao: InstructionDao,
    vault: VaultModeHolder,
) : ViewModel() {

    private val reload = MutableStateFlow(0)

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    /** Set when a save was refused. Held until the officer dismisses it or retries. */
    private val _mutationError = MutableStateFlow<String?>(null)
    val mutationError = _mutationError.asStateFlow()

    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()

    val state = combine(vault.mode, reload) { mode, _ -> mode }.flatMapLatest { mode ->
        if (mode != VaultMode.Visible) {
            flowOf(SubdivisionUiState(loading = false, available = false))
        } else {
            val records = combine(
                subdivisionDao.observeProfile(SubdivisionRepository.VISIBLE),
                subdivisionDao.observeStations(SubdivisionRepository.VISIBLE),
                subdivisionDao.observeMatters(SubdivisionRepository.VISIBLE),
                subdivisionDao.observePostings(SubdivisionRepository.VISIBLE),
                subdivisionDao.observeReviews(SubdivisionRepository.VISIBLE),
            ) { profile, stations, matters, postings, reviews ->
                Records(profile, stations, matters, postings, reviews)
            }
            val work = combine(
                people.observeAllInMode(SubdivisionRepository.VISIBLE),
                instructionDao.observeAll(),
            ) { contacts, rows ->
                // Hidden and sensitive records are removed here, once, so no downstream
                // list, count, picker or review has to remember to exclude them.
                val normalIds = contacts.filterNot { it.isSensitive }.mapTo(mutableSetOf()) { it.id }
                val visible = SubdivisionProjections.normalWorkspaceWork(rows.map { it.toDomain() }, normalIds)
                Work(contacts.filterNot { it.isSensitive }.sortedBy { it.name.lowercase() }, visible)
            }
            combine(records, work) { record, w ->
                SubdivisionUiState(
                    loading = false,
                    profile = record.profile,
                    stations = record.stations,
                    matters = record.matters,
                    postings = record.postings,
                    reviews = record.reviews,
                    contacts = w.contacts,
                    instructions = w.instructions,
                )
            }.onStart { emit(SubdivisionUiState(loading = true)) }
                .catch { failure ->
                    // The failure type only. Never a note, a name, or key material.
                    android.util.Log.w("Subdivision", "Subdivision read failed: ${failure.javaClass.simpleName}")
                    emit(
                        SubdivisionUiState(
                            loading = false,
                            error = "Your subdivision record could not load. Nothing you have saved has been changed.",
                        ),
                    )
                }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubdivisionUiState())

    private class Records(
        val profile: SubdivisionProfile?,
        val stations: List<Station>,
        val matters: List<Matter>,
        val postings: List<StaffPosting>,
        val reviews: List<SubdivisionReview>,
    )

    private class Work(val contacts: List<Person>, val instructions: List<Instruction>)

    fun retry() {
        _mutationError.value = null
        reload.value++
    }

    fun dismissError() { _mutationError.value = null }

    // ---- mutations ----

    fun saveProfile(name: String, district: String, officer: String, onSaved: () -> Unit) =
        mutate("Subdivision saved", onSaved) { repository.saveProfile(name, district, officer) }

    fun saveStation(id: String?, name: String, kind: String, notes: String, onSaved: (String) -> Unit) =
        mutateReturning(if (id == null) "Station added" else "Station saved", onSaved) {
            repository.saveStation(id, name, kind, notes)
        }

    fun archiveStation(id: String, archived: Boolean, onDone: () -> Unit = {}) =
        mutate(if (archived) "Station archived" else "Station reopened", onDone) {
            repository.archiveStation(id, archived)
        }

    fun saveStaff(
        personId: String,
        stationId: String?,
        responsibilities: String,
        isStaff: Boolean,
        active: Boolean,
        onSaved: () -> Unit,
    ) = mutate(if (isStaff) "Staff details saved" else "Removed from the staff list", onSaved) {
        repository.saveStaff(personId, stationId, responsibilities, isStaff, active)
    }

    fun saveMatter(
        id: String?,
        title: String,
        stationId: String?,
        reference: String,
        description: String,
        onSaved: (String) -> Unit,
    ) = mutateReturning(if (id == null) "Matter added" else "Matter saved", onSaved) {
        repository.saveMatter(id, title, stationId, reference, description)
    }

    fun archiveMatter(id: String, archived: Boolean, onDone: () -> Unit = {}) =
        mutate(if (archived) "Matter archived" else "Matter reopened", onDone) {
            repository.archiveMatter(id, archived)
        }

    fun changeWorkContext(instructionId: String, stationId: String?, matterId: String?, onSaved: () -> Unit) =
        mutate("Work context updated", onSaved) { repository.linkInstruction(instructionId, stationId, matterId) }

    fun recordReview(scopeKey: String, notes: String, onSaved: () -> Unit) =
        mutate("Review recorded", onSaved) { repository.saveReview(scopeKey, notes) }

    private fun mutate(success: String?, onSuccess: () -> Unit, work: suspend () -> Unit) {
        mutateReturning<Unit>(success, { onSuccess() }, work)
    }

    private fun <T> mutateReturning(success: String?, onSuccess: (T) -> Unit, work: suspend () -> T) {
        if (_busy.value) return
        _busy.value = true
        _mutationError.value = null
        viewModelScope.launch {
            try {
                val result = work()
                onSuccess(result)
                success?.let { events.trySend(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // The repository's guards use `require`, whose message is already written
                // for the officer. Anything else gets a neutral sentence that does not
                // pretend to know what went wrong, and never leaks a note or a name.
                _mutationError.value = failure.actionableMessage()
            } finally {
                _busy.value = false
            }
        }
    }

    private fun Exception.actionableMessage(): String = when (this) {
        is IllegalArgumentException, is IllegalStateException ->
            message?.takeIf { it.isNotBlank() } ?: FALLBACK_ERROR
        else -> FALLBACK_ERROR
    }

    private companion object {
        const val FALLBACK_ERROR =
            "That change could not be saved. Please try again; your existing records are unchanged."
    }
}
