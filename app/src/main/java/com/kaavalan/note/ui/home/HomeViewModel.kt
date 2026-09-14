package com.kaavalan.note.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.PersonStaleAge
import com.kaavalan.note.data.local.RoomPersonRepository
import com.kaavalan.note.data.local.TagDao
import com.kaavalan.note.data.tags.RoomTagRepository
import com.kaavalan.note.data.vault.VaultModeHolder
import com.kaavalan.note.ui.util.SafeError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val personRepository: RoomPersonRepository,
    private val instructionDao: InstructionDao,
    private val tagDao: TagDao,
    private val tagRepository: RoomTagRepository,
    private val vaultModeHolder: VaultModeHolder,
    private val contactSyncService: com.kaavalan.note.data.person.ContactSyncService,
    private val userDao: com.kaavalan.note.data.user.UserDao,
) : ViewModel() {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    private val messageChannel = kotlinx.coroutines.channels.Channel<String>(
        kotlinx.coroutines.channels.Channel.BUFFERED,
    )
    val messages = messageChannel.receiveAsFlow()

    /**
     * v2.x: who "from" is on a dispatched instruction. The device
     * owner row is created at first launch by
     * [com.kaavalan.note.data.user.UserBootstrap]; until the user
     * renames themselves it is the literal default "Device owner".
     * Only [com.kaavalan.note.data.user.UserEntity.displayName] exists
     * today -- there is no designation/station on that table -- so the
     * dispatch composer's designation/division stay null rather than
     * inventing a profile-editing feature nobody asked for.
     */
    val deviceOwnerName: StateFlow<String> = userDao.observeDeviceOwner()
        .map { it?.displayName.orEmpty() }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), "")

    init {
        viewModelScope.launch {
            vaultModeHolder.mode
                .flatMapLatest { mode ->
                    combine(
                        personRepository.observeAllInMode(mode.storageKey),
                        instructionDao.observeOpenCountByPerson(),
                        instructionDao.observeStaleByPerson(),
                        instructionDao.observeOutgoingOpen(),
                        instructionDao.observeIncomingOpen(),
                        tagDao.observeTop(20).map { it.map { e -> TagCount(e.id, e.name, e.usageCount) } },
                    ) { values ->
                        // values[0] is typed as List<Any> by the heterogeneous-flow combine,
                        // but observeAllInMode(...) emits List<Person> at runtime, so the
                        // filterIsInstance is a type-narrowing no-op that gives us a proper
                        // List<Person> without an unchecked cast.
                        val persons = values[0].filterIsInstance<com.kaavalan.note.data.person.Person>()
                        @Suppress("UNCHECKED_CAST")
                        val counts = values[1] as List<com.kaavalan.note.data.local.PersonOpenCount>
                        @Suppress("UNCHECKED_CAST")
                        val stale = values[2] as List<PersonStaleAge>
                        @Suppress("UNCHECKED_CAST")
                        val outgoing = values[3] as List<com.kaavalan.note.data.local.entities.InstructionEntity>
                        @Suppress("UNCHECKED_CAST")
                        val incoming = values[4] as List<com.kaavalan.note.data.local.entities.InstructionEntity>
                        @Suppress("UNCHECKED_CAST")
                        val popularTags = values[5] as List<TagCount>
                        val openMap = persons.associate { it.id to 0 } + counts.associate { it.personId to it.cnt }
                        val staleSet = stale.map { it.personId }.toSet()
                        if (persons.isEmpty() && outgoing.isEmpty() && incoming.isEmpty()) HomeUiState.Empty
                        else HomeUiState.Loaded(persons = persons, openCountByPersonId = openMap, stalePersonIds = staleSet, outgoingOpen = outgoing, incomingOpen = incoming, popularTags = popularTags)
                    }
                }
                .catch { e -> _state.value = HomeUiState.Error(SafeError.forUser(e, "Could not load people.")) }
                .collect { _state.value = it }
        }
        refreshTagsFromNetwork()
    }

    fun createPerson(name: String, designation: String?, station: String?) {
        viewModelScope.launch {
            runCatching { personRepository.create(name, designation, station) }
                .onFailure { e -> messageChannel.trySend(SafeError.forUser(e, "Could not create person.")) }
        }
    }

    /** Compatibility path for callers that still submit one directory row. */
    fun importContact(displayName: String, phone: String) {
        importContacts(
            listOf(com.kaavalan.note.data.person.ContactSyncService.ContactCandidate("manual:0", displayName, phone)),
        )
    }

    fun importContacts(contacts: List<com.kaavalan.note.data.person.ContactSyncService.ContactCandidate>) {
        viewModelScope.launch {
            runCatching {
                personRepository.importContacts(
                    contacts.map {
                        com.kaavalan.note.data.local.ImportedContact(
                            name = it.displayName.trim(),
                            phone = it.phone.trim().ifBlank { null },
                        )
                    },
                    vaultModeHolder.mode.value.storageKey,
                )
            }
                .onFailure { e -> messageChannel.trySend(SafeError.forUser(e, "Could not import contact.")) }
        }
    }

    /**
     * v2.0 (Hierarchy): expose the [ContactSyncService] to the
     * `ContactPickerSheet`. We use this from the screen (not the
     * sheet) so the sheet doesn't need to be `@HiltViewModel`. The
     * service is a Hilt singleton, lifetime-scoped to the
     * ApplicationContext, so this is safe.
     */
    fun contactSyncService(): com.kaavalan.note.data.person.ContactSyncService = contactSyncService

    private fun refreshTagsFromNetwork() {
        viewModelScope.launch {
            runCatching { tagRepository.refreshFromNetwork() }
                .onFailure { e -> messageChannel.trySend(SafeError.forUser(e, "Could not refresh tags.")) }
        }
    }
}
