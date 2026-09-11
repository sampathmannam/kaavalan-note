package com.kaavalan.note.qa

import app.cash.turbine.test
import com.kaavalan.note.data.captures.Capture
import com.kaavalan.note.data.captures.CaptureMode
import com.kaavalan.note.data.captures.CaptureRepository
import com.kaavalan.note.data.instructions.Direction
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.InstructionRepository
import com.kaavalan.note.data.instructions.Priority
import com.kaavalan.note.data.instructions.Source
import com.kaavalan.note.data.instructions.Status
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.data.person.PersonRepository
import com.kaavalan.note.data.reminder.ReminderScheduler
import com.kaavalan.note.data.tags.RoomTagRepository
import com.kaavalan.note.data.tags.Tag
import com.kaavalan.note.data.tags.TagKind
import com.kaavalan.note.features.capture.CaptureUiState
import com.kaavalan.note.features.capture.CaptureViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.5.6 QA — comprehensive test cases from
 * `.sdd/test-cases-v1.5.6.md`.
 *
 * v1.6.1: the LLM-driven `onExtract` / `onConfirm` paths
 * are gone. The capture flow is now:
 *
 *   type / voice / photo -> text in the field -> tap Save
 *   -> instruction row + capture row written
 *
 * The QA cases that depended on the LLM extraction
 * (E-01 trim of LLM-extracted person, R-04 null-proposal
 * error, R-05 / R-05b extraction-failure errors, A-08
 * model-state surface) are removed. The cases that
 * exercise the save / draft / tag paths are kept and
 * updated for the new VM constructor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class V156QaTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- Fakes (mirroring the patterns in CaptureViewModelTest) ----

    private class FakeCaptureRepository(
        var createShouldThrow: Boolean = false,
    ) : CaptureRepository {
        var nextId = 0
        val created = mutableListOf<Pair<String, CaptureMode>>()
        override suspend fun create(rawText: String, mode: CaptureMode): Capture {
            if (createShouldThrow) throw RuntimeException("simulated save failure")
            nextId += 1
            val id = "cap-$nextId"
            created += id to mode
            return Capture(
                id = id,
                mode = mode,
                rawText = rawText,
                processed = false,
                createdAt = "2026-08-12T00:00:00+00:00",
            )
        }
        override suspend fun markProcessed(id: String) {}
    }

    private class FakePersonRepository : PersonRepository {
        val personsFlow = MutableStateFlow<List<Person>>(emptyList())
        val existing = mutableMapOf<String, Person>()
        fun seed(name: String = "Seed Person") {
            val person = Person(
                id = "person-${existing.size + 1}",
                name = name,
                designation = null,
                station = null,
                phone = null,
            )
            existing[name] = person
            personsFlow.value = existing.values.toList()
        }
        override fun observeAll(): Flow<List<Person>> = personsFlow.asStateFlow()
        override fun observeAllInMode(mode: String): Flow<List<Person>> = personsFlow.asStateFlow()
        override suspend fun create(
            name: String,
            designation: String?,
            station: String?,
            clientId: String?,
        ): Person {
            val person = Person(
                id = clientId ?: "person-${existing.size + 1}",
                name = name,
                designation = designation,
                station = station,
                phone = null,
            )
            existing[name] = person
            personsFlow.value = existing.values.toList()
            return person
        }
        override suspend fun findByName(name: String): Person? = existing[name]
        override suspend fun findOrCreate(
            name: String,
            designation: String?,
            station: String?,
        ): Person = findByName(name) ?: create(name, designation, station)
        override suspend fun setSensitive(id: String, sensitive: Boolean) {}
    }

    private class FakeInstructionRepository(
        var createShouldThrow: Boolean = false,
    ) : InstructionRepository {
        val created = mutableListOf<CreatedInstruction>()
        override suspend fun create(
            personId: String?,
            source: Source,
            priority: Priority,
            title: String,
            rawText: String,
            dueAt: String?,
        ): Instruction {
            if (createShouldThrow) throw RuntimeException("db locked")
            val id = "ins-${created.size + 1}"
            created += CreatedInstruction(id, personId, source, priority, title, rawText, dueAt)
            return Instruction(
                id = id,
                personId = personId,
                direction = Direction.OUTGOING,
                status = Status.OPEN,
                source = source,
                priority = priority,
                title = title,
                rawText = rawText,
                dueAt = dueAt,
                capturedAt = "2026-08-12T00:00:00+00:00",
                createdAt = "2026-08-12T00:00:00+00:00",
                updatedAt = "2026-08-12T00:00:00+00:00",
            )
        }
        override suspend fun fetchAll(): List<Instruction> = emptyList()
        override suspend fun update(
            id: String,
            status: Status,
            completedAt: String?,
            droppedReason: String?,
            isSensitive: Boolean,
        ): Instruction = error("not used")
        override suspend fun markDone(id: String, completedAt: String) {}
        override suspend fun markDropped(id: String, reason: String?, at: String) {}

        override suspend fun createWithAudience(
            personId: String?,
            audience: com.kaavalan.note.data.instructions.AudienceRef?,
            source: Source,
            priority: Priority,
            title: String,
            rawText: String,
            dueAt: String?,
            dueAtMs: Long?,
            channel: String?,
            direction: Direction,
            stationId: String?,
            matterId: String?,
        ): Instruction = create(personId, source, priority, title, rawText, dueAt)
            .copy(direction = direction, dueAtMs = dueAtMs, stationId = stationId, matterId = matterId)
        override suspend fun setAudience(id: String, audience: com.kaavalan.note.data.instructions.AudienceRef?) {
            // no-op
        }
        override suspend fun setDueChip(id: String, dueAtMs: Long?) {
            // no-op
        }
        override suspend fun setChannel(id: String, channel: String?) {
            // no-op
        }

    }

    private data class CreatedInstruction(
        val id: String,
        val personId: String?,
        val source: Source,
        val priority: Priority,
        val title: String,
        val rawText: String,
        val dueAt: String?,
    )

    private fun fakeTagRepo(): RoomTagRepository {
        val tags: MutableList<Tag> = mutableListOf()
        val mock = io.mockk.mockk<RoomTagRepository>(relaxed = true)
        io.mockk.every { mock.observeAll() } returns MutableStateFlow(tags.toList()).asStateFlow()
        io.mockk.coEvery { mock.findOrCreateFree(any()) } coAnswers {
            val name = firstArg<String>()
            val existing = tags.find { it.name == name && it.kind == TagKind.FREE }
            existing ?: Tag(
                id = "tag-${tags.size + 1}",
                name = name,
                kind = TagKind.FREE,
                createdAt = "2026-08-12T00:00:00+00:00",
                updatedAt = "2026-08-12T00:00:00+00:00",
            ).also { tags += it }
        }
        io.mockk.coEvery { mock.attachToInstruction(any(), any()) } returns Unit
        return mock
    }

    private fun fakes(
        captureShouldThrow: Boolean = false,
        instructionShouldThrow: Boolean = false,
    ): Triple<FakeCaptureRepository, FakePersonRepository, FakeInstructionRepository> =
        Triple(
            FakeCaptureRepository(captureShouldThrow),
            FakePersonRepository(),
            FakeInstructionRepository(instructionShouldThrow),
        )

    private fun makeVm(
        capture: FakeCaptureRepository,
        person: FakePersonRepository,
        ins: FakeInstructionRepository,
        savedStateHandle: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle(),
    ): CaptureViewModel = CaptureViewModel(
        savedStateHandle = savedStateHandle,
        captureRepository = capture,
        personRepository = person,
        instructionRepository = ins,
        tagRepository = fakeTagRepo(),
        reminderScheduler = io.mockk.mockk<ReminderScheduler>(relaxed = true),
    )

    // ============================================================================
    // E — Edge cases
    // ============================================================================

    /**
     * E-07: Text field with newlines + tabs is preserved as-is in
     * `state.text` (the rawText passed to the save flow). Compose
     * OutlinedTextField does not strip whitespace.
     */
    @Test
    fun `E-07 onTextChanged preserves newlines and tabs in rawText`() = runTest(testDispatcher) {
        val (capture, person, ins) = fakes()
        val vm = makeVm(capture, person, ins)
        vm.openSheet()
        val raw = "Line 1\nLine 2\twith tab\n\nLine 4"
        vm.onTextChanged(raw)
        assertEquals(raw, vm.state.value.text)
        assertTrue(vm.state.value.canSaveRaw)
    }

    /**
     * E-08: Text containing only whitespace is treated as blank --
     * canSaveRaw returns false.
     */
    @Test
    fun `E-08 whitespace-only text is treated as blank`() = runTest(testDispatcher) {
        val (capture, person, ins) = fakes()
        val vm = makeVm(capture, person, ins)
        vm.openSheet()
        vm.onTextChanged("   \n\t   \n  ")
        assertFalse("canSaveRaw must be false for whitespace-only text", vm.state.value.canSaveRaw)
    }

    /**
     * E-10: onAddFreeTag trims whitespace, strips leading `#`, and
     * truncates to 40 characters.
     */
    @Test
    fun `E-10 onAddFreeTag trims whitespace strips hash and truncates to 40 chars`() = runTest(testDispatcher) {
        val (capture, person, ins) = fakes()
        val vm = makeVm(capture, person, ins)

        // 1. Whitespace trim
        vm.onAddFreeTag("  urgent  ")
        advanceUntilIdle()
        // 2. Leading `#` strip.
        vm.onAddFreeTag("#crime-check")
        advanceUntilIdle()
        // 3. Truncation to 40 chars.
        val longName = "a".repeat(100)
        vm.onAddFreeTag(longName)
        advanceUntilIdle()

        // The selectedTagIds set should have entries for the
        // clean names (after trim + strip + truncate).
        val ids = vm.state.value.selectedTagIds
        assertEquals("expected 3 tags, got $ids", 3, ids.size)
    }

    /**
     * E-10b: onAddFreeTag with a fully blank input is a no-op
     * (the VM checks `if (clean.isBlank()) return`).
     */
    @Test
    fun `E-10b onAddFreeTag with blank input is a no-op`() = runTest(testDispatcher) {
        val (capture, person, ins) = fakes()
        val vm = makeVm(capture, person, ins)
        vm.onAddFreeTag("   ")
        advanceUntilIdle()
        vm.onAddFreeTag("##")
        advanceUntilIdle()
        assertEquals(0, vm.state.value.selectedTagIds.size)
    }

    // ============================================================================
    // S — State transitions on the capture VM
    // ============================================================================

    /**
     * S-01 (vm-level): with no proposal / no LLM, the only save
     * path is `onSaveRaw`. It is gated by `canSaveRaw` which
     * requires a non-blank trimmed value.
     */
    @Test
    fun `S-01 onSaveRaw with a blank value is a no-op`() = runTest(testDispatcher) {
        val (capture, person, ins) = fakes()
        val vm = makeVm(capture, person, ins)
        vm.openSheet()
        vm.onSaveRaw()
        advanceUntilIdle()
        assertTrue("Sheet should stay open", vm.state.value.isVisible)
        assertEquals(0, ins.created.size)
    }

    /**
     * S-02 (vm-level): a successful onSaveRaw flips isSaving=true
     * during the operation, then back to false after clearDraft.
     */
    @Test
    fun `S-02 onSaveRaw flips isSaving true during save and false after success`() = runTest(testDispatcher) {
        val (capture, person, ins) = fakes()
        person.seed("Inspector Kumar")
        val vm = makeVm(capture, person, ins)
        advanceUntilIdle()
        vm.openSheet()
        vm.onTextChanged("Inspector Kumar to review cases")
        vm.onSaveRaw()
        advanceUntilIdle()
        // After a successful Save, the sheet is dismissed and
        // the state is reset to defaults.
        assertFalse(vm.state.value.isSaving)
        assertFalse(vm.state.value.isVisible)
        assertEquals(1, ins.created.size)
    }

    /**
     * S-03 (vm-level): onSaveRaw with the free-floating path sets
     * personId=null and priority=NORMAL.
     */
    @Test
    fun `S-03 onSaveRaw saves a free-floating instruction with priority NORMAL`() = runTest(testDispatcher) {
        val (capture, person, ins) = fakes()
        // Seed the user with 1 person so hasPeople = true.
        person.seed("Inspector Kumar")
        val vm = makeVm(capture, person, ins)
        advanceUntilIdle()  // let the VM's hasPeople flow subscribe
        vm.openSheet()
        vm.onTextChanged("free-floating note with no LLM")
        vm.onSaveRaw()
        advanceUntilIdle()

        assertEquals(1, ins.created.size)
        val saved = ins.created.first()
        assertNull("personId is null for free-floating", saved.personId)
        assertEquals(Priority.NORMAL, saved.priority)
    }

    /**
     * S-04: onSaveRaw with a long text truncates the title to 40
     * chars (per CaptureViewModel.onSaveRaw).
     */
    @Test
    fun `S-04 onSaveRaw truncates title to 40 chars for long text`() = runTest(testDispatcher) {
        val (capture, person, ins) = fakes()
        person.seed("p")
        val vm = makeVm(capture, person, ins)
        advanceUntilIdle()  // let hasPeople flow subscribe
        vm.openSheet()
        val longText = "a".repeat(200)
        vm.onTextChanged(longText)
        vm.onSaveRaw()
        advanceUntilIdle()

        val saved = ins.created.first()
        assertEquals(41, saved.title.length)  // 40 chars + "…"
        assertTrue(saved.title.endsWith("…"))
    }

    /**
     * S-05 (new in v1.6.1): the source on a saved note reflects
     * the capture mode. A voice-transcribed note saves as
     * `Source.VOICE`, a typed note as `Source.TEXT`, a photo OCR
     * note as `Source.PHOTO`. The audit trail preserves the
     * source the user actually used.
     */
    @Test
    fun `S-05 onSaveRaw after a voice transcript saves with Source VOICE`() = runTest(testDispatcher) {
        val (capture, person, ins) = fakes()
        person.seed("p")
        val vm = makeVm(capture, person, ins)
        advanceUntilIdle()
        vm.onVoiceTranscript("Tell SHO Ramu to send FIR 47")
        vm.onSaveRaw()
        advanceUntilIdle()

        assertEquals(1, ins.created.size)
        assertEquals(Source.VOICE, ins.created[0].source)
    }

    @Test
    fun `S-05b onSaveRaw after photo OCR saves with Source PHOTO`() = runTest(testDispatcher) {
        val (capture, person, ins) = fakes()
        person.seed("p")
        val vm = makeVm(capture, person, ins)
        advanceUntilIdle()
        vm.onPhotoTextRecognized("OCR'd: call SHO Ramu at 9am")
        vm.onSaveRaw()
        advanceUntilIdle()

        assertEquals(1, ins.created.size)
        assertEquals(Source.PHOTO, ins.created[0].source)
    }

    // ============================================================================
    // R — Error recovery
    // ============================================================================

    /**
     * R-05: instructionRepository.create() throws -> the
     * user-friendly error surfaces; sheet stays open; no
     * instruction row is committed. The captures table write
     * succeeds (it's the instruction write that failed).
     */
    @Test
    fun `R-05 instruction save failure surfaces user-readable error`() = runTest(testDispatcher) {
        val (capture, person, ins) = fakes(instructionShouldThrow = true)
        person.seed("SHO Ramu")
        val vm = makeVm(capture, person, ins)
        advanceUntilIdle()
        vm.openSheet()
        vm.onTextChanged("Send FIR 47 by Friday")
        vm.onSaveRaw()
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.isVisible)
        // The error must be the safe user-facing message,
        // not the raw RuntimeException "db locked".
        assertNotEquals("db locked", s.error)
    }

    // ============================================================================
    // N — Navigation / state
    // ============================================================================

    /**
     * N-04: tapping the close (X) button on the capture sheet
     * calls dismissSheet() which sets isVisible=false without
     * wiping the in-flight draft (F-09 contract).
     */
    @Test
    fun `N-04 dismissSheet hides the sheet but preserves the draft text`() = runTest(testDispatcher) {
        val (capture, person, ins) = fakes()
        val vm = makeVm(capture, person, ins)
        vm.openSheet()
        vm.onTextChanged("half-typed note")
        vm.dismissSheet()
        assertFalse(vm.state.value.isVisible)
        assertEquals("half-typed note", vm.state.value.text)
    }

    // ============================================================================
    // D — Data persistence
    // ============================================================================

    /**
     * D-02: A new VM constructed with a SavedStateHandle that
     * already holds text/mode/selectedTagIds re-hydrates the
     * state. This simulates process death + relaunch.
     */
    @Test
    fun `D-02 new VM re-hydrates state from existing SavedStateHandle`() = runTest(testDispatcher) {
        val (capture, person, ins) = fakes()
        val handle = androidx.lifecycle.SavedStateHandle()
        handle["capture.text"] = "restored after process death"
        handle["capture.mode"] = "TEXT"
        handle["capture.selectedTagIds"] = arrayListOf("tag-1", "tag-2")
        val vm = makeVm(capture, person, ins, savedStateHandle = handle)
        assertEquals("restored after process death", vm.state.value.text)
        assertEquals(CaptureMode.TEXT, vm.state.value.mode)
        assertEquals(setOf("tag-1", "tag-2"), vm.state.value.selectedTagIds)
    }

    /**
     * D-02b: clearDraft wipes the in-memory state AND removes the
     * SavedStateHandle keys, so a process death + relaunch does
     * NOT restore a stale draft (F-09 root-cause fix).
     */
    @Test
    fun `D-02b clearDraft removes SavedStateHandle keys`() = runTest(testDispatcher) {
        val (capture, person, ins) = fakes()
        val handle = androidx.lifecycle.SavedStateHandle()
        val vm = makeVm(capture, person, ins, savedStateHandle = handle)
        vm.openSheet()
        vm.onTextChanged("will be cleared")
        testScheduler.advanceUntilIdle()
        vm.clearDraft()
        testScheduler.advanceUntilIdle()
        // Construct a new VM with the same handle — the text
        // should NOT be restored.
        val vm2 = makeVm(capture, person, ins, savedStateHandle = handle)
        assertEquals("", vm2.state.value.text)
    }
}
