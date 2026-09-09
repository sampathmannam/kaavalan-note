package com.kaavalan.note.ui.workspace

import com.kaavalan.note.data.instructions.RoomInstructionRepository
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.RoomPersonRepository
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.data.reminder.ReminderManager
import com.kaavalan.note.data.vault.VaultMode
import com.kaavalan.note.data.vault.VaultModeHolder
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {
    @Test fun `workspace responds to vault changes without retaining hidden rows`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var model: WorkspaceViewModel? = null
        try {
            val dao = mockk<InstructionDao>()
            val people = mockk<RoomPersonRepository>()
            val vault = VaultModeHolder()
            val visible = Person("visible", "Visible officer", null, null, null)
            val hidden = Person("hidden", "Private officer", null, null, null)
            every { people.observeAllInMode("visible") } returns flowOf(listOf(visible))
            every { people.observeAllInMode("hidden") } returns flowOf(listOf(hidden))
            every { dao.observeAll() } returns flowOf(listOf(entity("v", "visible"), entity("h", "hidden")))
            val vm = WorkspaceViewModel(dao, people, mockk(), mockk(), vault, mockk(), users()).also { model = it }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            advanceUntilIdle()
            assertEquals(listOf("v"), vm.state.value.instructions.map { it.id })
            vault.setMode(VaultMode.Hidden)
            advanceUntilIdle()
            assertEquals(listOf("h"), vm.state.value.instructions.map { it.id })
            assertEquals(listOf(hidden), vm.state.value.contacts)
        } finally {
            // Cancel subscribers and the ViewModel before restoring the platform dispatcher.
            backgroundScope.cancel()
            model?.viewModelScope?.cancel()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test fun `failed write keeps detail open and reports an error`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var model: WorkspaceViewModel? = null
        try {
            val dao = mockk<InstructionDao>()
            every { dao.observeAll() } returns flowOf(listOf(entity("note", null)))
            val people = mockk<RoomPersonRepository>()
            every { people.observeAllInMode(any()) } returns flowOf(emptyList())
            val repo = mockk<RoomInstructionRepository>()
            coEvery { repo.markDone("note") } throws IllegalStateException("database busy")
            val reminders = mockk<ReminderManager>(relaxed = true)
            val vm = WorkspaceViewModel(dao, people, repo, reminders, VaultModeHolder(), mockk(), users()).also { model = it }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            advanceUntilIdle()
            var dismissed = false
            vm.complete("note") { dismissed = true }
            advanceUntilIdle()
            assertFalse(dismissed)
            assertFalse(vm.busy.value)
            assertTrue(vm.messages.first().contains("try again"))
            verify(exactly = 0) { reminders.cancelDelivery(any()) }
            assertEquals("note", vm.state.value.instructions.single().id)
        } finally {
            // Cancel subscribers and the ViewModel before restoring the platform dispatcher.
            backgroundScope.cancel()
            model?.viewModelScope?.cancel()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test fun `import retains the selected phone and stores name-only contacts without an empty phone`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var model: WorkspaceViewModel? = null
        try {
            val dao = mockk<InstructionDao> { every { observeAll() } returns flowOf(emptyList()) }
            val people = mockk<RoomPersonRepository>(relaxed = true) {
                every { observeAllInMode(any()) } returns flowOf(emptyList())
            }
            val vault = VaultModeHolder().apply { setMode(VaultMode.Hidden) }
            val vm = WorkspaceViewModel(dao, people, mockk(), mockk(), vault, mockk(), users()).also { model = it }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            advanceUntilIdle()
            var successes = 0
            vm.importContact(" Officer ", " +91 5550100 ") { successes++ }
            advanceUntilIdle()
            vm.importContact("Name only", "") { successes++ }
            advanceUntilIdle()
            coVerify(exactly = 1) { people.createContact("Officer", null, null, "+91 5550100", "hidden") }
            coVerify(exactly = 1) { people.createContact("Name only", null, null, null, "hidden") }
            assertEquals(2, successes)
        } finally {
            backgroundScope.cancel()
            model?.viewModelScope?.cancel()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test fun `failed import keeps picker open and allows retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var model: WorkspaceViewModel? = null
        try {
            val dao = mockk<InstructionDao> { every { observeAll() } returns flowOf(emptyList()) }
            val people = mockk<RoomPersonRepository>(relaxed = true) {
                every { observeAllInMode(any()) } returns flowOf(emptyList())
            }
            coEvery { people.createContact(any(), any(), any(), any(), any(), any()) } throws IllegalStateException()
            val vm = WorkspaceViewModel(dao, people, mockk(), mockk(), VaultModeHolder(), mockk(), users()).also { model = it }
            var dismissed = false
            vm.importContact("Officer", "5550100") { dismissed = true }
            advanceUntilIdle()
            assertFalse(dismissed)
            assertFalse(vm.busy.value)
            assertTrue(vm.messages.first().contains("try again"))
        } finally {
            backgroundScope.cancel()
            model?.viewModelScope?.cancel()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    private fun users() = mockk<com.kaavalan.note.data.user.UserDao> {
        every { observeDeviceOwner() } returns flowOf(null)
    }

    private fun entity(id: String, person: String?) = InstructionEntity(
        id = id, personId = person, direction = "SELF", status = "OPEN", source = "TEXT", priority = "NORMAL",
        title = "Test instruction", rawText = "Test instruction", dueAt = null,
        capturedAt = "2026-09-08T09:00:00Z", createdAt = "2026-09-08T09:00:00Z", updatedAt = "2026-09-08T09:00:00Z",
    )
}
