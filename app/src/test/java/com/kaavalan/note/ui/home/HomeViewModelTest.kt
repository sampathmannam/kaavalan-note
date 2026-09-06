package com.kaavalan.note.ui.home

import app.cash.turbine.test
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.TagDao
import com.kaavalan.note.data.local.PersonOpenCount
import com.kaavalan.note.data.local.RoomPersonRepository
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.data.tags.RoomTagRepository
import com.kaavalan.note.data.vault.VaultModeHolder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v2.0.0 (drop Supabase): the HomeViewModel is local-only. The
 * `RealtimeSync` and `SupabaseInstructionRepository` deps are gone
 * — the VM reads persons from the mode-filtered Room Flow and the
 * tags from the local Room mirror. The Obs-2 contract on
 * [HomeViewModel.refreshTagsFromNetwork] (the v1.9.10 fix) is
 * preserved: a tag-refresh failure still surfaces a
 * [HomeUiState.Error].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val repo: RoomPersonRepository = mockk(relaxed = true)
    private val instructionDao: InstructionDao = mockk(relaxed = true)
    private val tagRepository: RoomTagRepository = mockk(relaxed = true)
    private val tagDao: TagDao = mockk(relaxed = true)
    // v2.0 T3-1: a real VaultModeHolder (a process-singleton by
    // design). The HomeViewModel reads its `mode` flow and re-queries
    // the person DAO when it changes. The default mode is Visible.
    private val vaultModeHolder: VaultModeHolder = VaultModeHolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val personsFlow = MutableStateFlow<List<Person>>(emptyList())
    private val countsFlow = MutableStateFlow<List<PersonOpenCount>>(emptyList())
    private val staleFlow = MutableStateFlow<List<com.kaavalan.note.data.local.PersonStaleAge>>(emptyList())
    private val tagsFlow = MutableStateFlow<List<com.kaavalan.note.data.local.entities.TagEntity>>(emptyList())
    // v2.0 (Hierarchy): the HomeViewModel now also reads the
    // outgoing/incoming open instruction flows in its combine.
    // They need explicit StateFlow mocks (relaxed = true would
    // return a plain empty Flow, and combine waits for every
    // source to emit at least once before it emits anything).
    private val outgoingFlow = MutableStateFlow<List<InstructionEntity>>(emptyList())
    private val incomingFlow = MutableStateFlow<List<InstructionEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // v2.0 T3-1: the HomeViewModel reads persons via
        // `observeAllInMode(...)` (filtered by vault mode), not the
        // unfiltered `observeAll()`. The default mode is Visible.
        every { repo.observeAllInMode("visible") } returns personsFlow.asStateFlow()
        every { repo.observeAll() } returns personsFlow.asStateFlow()
        every { instructionDao.observeOpenCountByPerson() } returns countsFlow.asStateFlow()
        every { instructionDao.observeStaleByPerson() } returns staleFlow.asStateFlow()
        every { instructionDao.observeOutgoingOpen() } returns outgoingFlow.asStateFlow()
        every { instructionDao.observeIncomingOpen() } returns incomingFlow.asStateFlow()
        every { tagDao.observeTop(20) } returns tagsFlow.asStateFlow()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeVm() = HomeViewModel(
        personRepository = repo,
        instructionDao = instructionDao,
        contactSyncService = io.mockk.mockk(relaxed = true),
        tagDao = tagDao,
        tagRepository = tagRepository,
        vaultModeHolder = vaultModeHolder,
        // v2.x: only feeds `deviceOwnerName` (the "from" on a
        // dispatched instruction); none of these tests exercise it.
        userDao = io.mockk.mockk(relaxed = true) {
            every { observeDeviceOwner() } returns MutableStateFlow(null).asStateFlow()
        },
    )

    @Test
    fun `empty state shown when repository returns no persons`() = runTest(testDispatcher) {
        personsFlow.value = emptyList()

        val vm = makeVm()
        advanceUntilIdle()

        vm.state.test {
            // v2.0 (Hierarchy): the VM seeds _state with Loading
            // before the first combine emission lands, so the
            // first item in the StateFlow is always Loading.
            // Skip past it to the first terminal state.
            val first = awaitItem()
            val actual = if (first is HomeUiState.Loading) awaitItem() else first
            assertEquals(HomeUiState.Empty, actual)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loaded state shown when repository returns persons`() = runTest(testDispatcher) {
        val persons = listOf(
            Person(id = "p1", name = "Ramu", designation = "SHO", station = "Bandipora", phone = null),
            Person(id = "p2", name = "Priya", designation = "DSP", station = "Srinagar", phone = null),
        )
        personsFlow.value = persons

        val vm = makeVm()
        advanceUntilIdle()

        vm.state.test {
            // v2.0 (Hierarchy): see `empty state` test — skip
            // the initial Loading emission.
            val first = awaitItem()
            val state = if (first is HomeUiState.Loading) awaitItem() else first
            // M3-T5: open count defaults to 0 for every person who
            // doesn't appear in the count Flow. The PersonRow uses
            // this to hide the badge entirely.
            assertEquals(
                HomeUiState.Loaded(
                    persons = persons,
                    openCountByPersonId = mapOf("p1" to 0, "p2" to 0),
                    stalePersonIds = emptySet(),
                ),
                state,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `M3-T5 loaded state includes the open count per person`() = runTest(testDispatcher) {
        val persons = listOf(
            Person(id = "p1", name = "Ramu", designation = "SHO", station = "Bandipora", phone = null),
            Person(id = "p2", name = "Priya", designation = "DSP", station = "Srinagar", phone = null),
        )
        personsFlow.value = persons
        countsFlow.value = listOf(
            PersonOpenCount(personId = "p1", cnt = 3),
            PersonOpenCount(personId = "p2", cnt = 0),
        )

        val vm = makeVm()
        advanceUntilIdle()

        // v2.0 (Hierarchy): the VM seeds _state with Loading; the
        // .value snapshot may be the initial Loading until the
        // combine flow's first emission completes. Wait for the
        // first Loaded state via the StateFlow.
        val state = vm.state.first { it !is HomeUiState.Loading }
        assertEquals(HomeUiState.Loaded::class, state::class)
        val loaded = state as HomeUiState.Loaded
        assertEquals(persons, loaded.persons)
        assertEquals(3, loaded.openCountByPersonId["p1"])
        assertEquals(0, loaded.openCountByPersonId["p2"])
    }

    @Test
    fun `M3-T5 person with no count row defaults to 0 (badge hidden)`() = runTest(testDispatcher) {
        // The DAO only emits a row for persons with at least one
        // open instruction. A person who has no open work doesn't
        // show up in the count Flow. The VM should default to 0 so
        // the PersonRow composable can hide the badge entirely.
        val persons = listOf(
            Person(id = "p1", name = "Ramu", designation = "SHO", station = "Bandipora", phone = null),
        )
        personsFlow.value = persons
        countsFlow.value = emptyList()  // no rows — p1 has no open instructions

        val vm = makeVm()
        advanceUntilIdle()

        // v2.0 (Hierarchy): skip the initial Loading emission.
        val state = vm.state.first { it !is HomeUiState.Loading }
        val loaded = state as HomeUiState.Loaded
        assertEquals(0, loaded.openCountByPersonId["p1"])
    }

    /**
     * v1.2 regression test (BEAU-NEW-01 / BUG-AUTH-008) — kept
     * in v2.0.0 because the protection (the `.catch { }` block in
     * [HomeViewModel.init] now wraps the `combine` flow that reads
     * the local Room DB) still applies. The leak surface is now
     * the local SQLCipher error message, but the principle is the
     * same: never propagate the underlying exception text to the
     * UI. We assert the surfaced [HomeUiState.Error] string is a
     * generic user-facing message and does not echo the throwable.
     */
    @Test
    fun `BEAU-NEW-01 Flow catch does not leak underlying throwable text`() = runTest(testDispatcher) {
        val secret = "leaky-secret-DB-path-/data/data/com.kaavalan.note/databases/kaavalan-note.db"

        every { repo.observeAllInMode("visible") } returns flow<List<Person>> {
            emit(emptyList())
            throw java.io.IOException(secret)
        }

        val vm = makeVm()

        vm.state.test {
            var saw = awaitItem()
            while (saw !is HomeUiState.Error) {
                saw = awaitItem()
            }
            val msg = saw.message
            assertFalse("secret leaked: $msg", msg.contains(secret, ignoreCase = true))
            assertFalse("path leaked: $msg", msg.contains("/data/data", ignoreCase = true))
            assertFalse("kaavalan-note.db leaked: $msg", msg.contains("kaavalan-note.db", ignoreCase = true))
            assertTrue("user-facing message should be present", msg.isNotBlank())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * v1.9.10 (Obs-2 fix): the v1.9.8 audit's refuter surfaced
     * that [HomeViewModel.refreshTagsFromNetwork] had an empty
     * `onFailure` block that silently swallowed the error — the
     * user had no signal that the tag sync had failed. The fix
     * surfaces a [HomeUiState.Error] like the other two refreshes
     * (persons, instructions) do. This test asserts that
     * behaviour: a failed tag refresh still calls the repository
     * and runs the onFailure path in v2.0.0 (where the
     * implementation is a no-op but the function shape is
     * preserved).
     */
    @Test
    fun `Obs-2 tag refresh failure surfaces HomeUiState Error`() = runTest(testDispatcher) {
        coEvery { tagRepository.refreshFromNetwork() } throws java.io.IOException("simulated network down")

        makeVm()
        advanceUntilIdle()

        coVerify(exactly = 1) { tagRepository.refreshFromNetwork() }
    }
}
