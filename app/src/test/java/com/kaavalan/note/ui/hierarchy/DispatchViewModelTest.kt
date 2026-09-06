package com.kaavalan.note.ui.hierarchy

import com.kaavalan.note.data.instructions.AudienceRef
import com.kaavalan.note.data.instructions.DeliveryService
import com.kaavalan.note.data.instructions.Direction
import com.kaavalan.note.data.instructions.Instruction
import com.kaavalan.note.data.instructions.InstructionRepository
import com.kaavalan.note.data.instructions.Priority
import com.kaavalan.note.data.instructions.Source
import com.kaavalan.note.data.instructions.Status
import com.kaavalan.note.data.person.Person
import com.kaavalan.note.data.person.PersonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v2.x (BUG FIX regression guard, found via adversarial QA audit):
 * pins the fix for a real UX-friction bug in
 * [DispatchViewModel.toggleChannel].
 *
 * **The finding.** `toggleChannel` refuses to let the
 * selected-channel set become empty (submit requires at least one
 * channel), but the guard was a silent no-op: tapping the sole
 * checked channel chip (e.g. SMS alone) produced no toast, no
 * visual change, and was indistinguishable from an unresponsive
 * control.
 *
 * **The fix.** `toggleChannel` now sends a one-shot message
 * through a new `infoChannel` (mirroring
 * [com.kaavalan.note.features.capture.CaptureViewModel]'s
 * `infoChannel` / `infoMessages` pattern -- see that file and
 * `features/capture/CaptureSheet.kt`'s Snackbar collection) when
 * the guard fires, instead of updating state with no observable
 * change. [DispatchSheet] collects `infoMessages` and surfaces it
 * as a Snackbar, the same wiring `CaptureSheet` uses for its own
 * `infoMessages`.
 *
 * These are plain unit tests against the ViewModel's public
 * surface (`state`, `infoChannel`) -- no Compose / Robolectric
 * involved, since the bug and the fix are both pure view-model
 * logic. [InstructionRepository] and [DeliveryService] are mocked
 * with `relaxed = true` because `toggleChannel` never touches
 * them; [PersonRepository] is stubbed just enough to satisfy the
 * `init` block's `refreshRoster()` call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DispatchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeVm(): DispatchViewModel {
        val instructionRepository = mockk<InstructionRepository>(relaxed = true)
        val personRepository = mockk<PersonRepository>(relaxed = true)
        every { personRepository.observeAll() } returns MutableStateFlow(emptyList<Person>()).asStateFlow()
        val deliveryService = mockk<DeliveryService>(relaxed = true)
        return DispatchViewModel(
            instructionRepository = instructionRepository,
            personRepository = personRepository,
            deliveryService = deliveryService,
        )
    }

    @Test
    fun `toggleChannel on the sole selected channel leaves the set unchanged`() = runTest(testDispatcher) {
        val vm = makeVm()
        advanceUntilIdle()

        // Default state starts with only SMS selected.
        assertEquals(setOf(DeliveryService.Channel.SMS), vm.state.value.channels)

        // Tapping the sole checked chip must not empty the set --
        // submit requires at least one channel selected.
        vm.toggleChannel(DeliveryService.Channel.SMS)
        advanceUntilIdle()

        assertEquals(
            "toggling the only selected channel must not change the selected set",
            setOf(DeliveryService.Channel.SMS),
            vm.state.value.channels,
        )
    }

    @Test
    fun `toggleChannel on the sole selected channel emits a user-facing info message`() = runTest(testDispatcher) {
        val vm = makeVm()
        advanceUntilIdle()

        vm.toggleChannel(DeliveryService.Channel.SMS)
        advanceUntilIdle()

        // This is the actual bug: the guard used to be silent. The
        // fix must emit something the Sheet can show as a Snackbar
        // so the tap doesn't look like a broken control.
        val msg = vm.infoChannel.tryReceive().getOrNull()
        assertNotNull("blocked toggle must emit an info message explaining why nothing changed", msg)
        assertTrue(
            "info message must explain that a channel must stay selected",
            msg!!.contains("channel", ignoreCase = true),
        )
    }

    @Test
    fun `toggleChannel that changes the set does not emit an info message`() = runTest(testDispatcher) {
        val vm = makeVm()
        advanceUntilIdle()

        // Add WHATSAPP -- the set is no longer a singleton.
        vm.toggleChannel(DeliveryService.Channel.WHATSAPP)
        advanceUntilIdle()
        assertEquals(
            setOf(DeliveryService.Channel.SMS, DeliveryService.Channel.WHATSAPP),
            vm.state.value.channels,
        )
        assertNull(
            "a toggle that actually changes the set must not emit an info message",
            vm.infoChannel.tryReceive().getOrNull(),
        )

        // Now removing SMS is legal -- WHATSAPP is still selected,
        // so the set never goes empty.
        vm.toggleChannel(DeliveryService.Channel.SMS)
        advanceUntilIdle()
        assertEquals(setOf(DeliveryService.Channel.WHATSAPP), vm.state.value.channels)
        assertNull(
            "unchecking a non-sole channel must not emit an info message",
            vm.infoChannel.tryReceive().getOrNull(),
        )
    }

    @Test
    fun `toggleChannel on the sole selected channel keeps submit eligible (audience permitting)`() = runTest(testDispatcher) {
        val vm = makeVm()
        advanceUntilIdle()

        vm.toggleChannel(DeliveryService.Channel.WHATSAPP) // {SMS, WHATSAPP}
        vm.toggleChannel(DeliveryService.Channel.SMS) // {WHATSAPP}
        advanceUntilIdle()
        vm.toggleChannel(DeliveryService.Channel.WHATSAPP) // blocked: would empty the set
        advanceUntilIdle()

        assertEquals(
            "the blocked toggle must leave the single remaining channel selected",
            setOf(DeliveryService.Channel.WHATSAPP),
            vm.state.value.channels,
        )
    }

    /**
     * v2.x (BUG FIX regression guard, found via adversarial QA
     * audit): pins the fix for [DispatchViewModel.setDue] silently
     * accepting a past due date.
     *
     * **The finding.** `DueChip`'s `DatePickerDialog` has no
     * minimum-date restriction -- a past date was fully selectable
     * and flowed straight into `state.dueAtMs`, which
     * `DispatchViewModel.submit` hands to
     * `HeaderTemplate.wrap`, rendering it verbatim into the
     * outgoing SMS/WhatsApp message (e.g. "Due: 2024-01-01 12:00"
     * on a message actually sent today). None of the past-date
     * handling `features/capture/CalendarGate.kt` already has for
     * the same "date resolved to before now" case applied here --
     * `CalendarGate.buildEventData` returns
     * `Skipped(SkipReason.IN_PAST)` rather than honoring a stale
     * date (see `CalendarGateTest`).
     *
     * **The fix.** `setDue` now refuses a `dueAtMs` that is already
     * in the past and sends a one-shot message through the
     * existing `infoChannel` explaining why, the same
     * refuse-and-explain shape `toggleChannel`'s own guard uses
     * above (and the same `infoChannel` / `infoMessages` /
     * Snackbar wiring `DispatchSheet` already collects for that
     * guard).
     */
    @Test
    fun `setDue with a past timestamp leaves dueAtMs unchanged`() = runTest(testDispatcher) {
        val vm = makeVm()
        advanceUntilIdle()

        assertNull("no due date set by default", vm.state.value.dueAtMs)

        val pastMs = System.currentTimeMillis() - 24 * 60 * 60 * 1000L // yesterday
        vm.setDue(pastMs)
        advanceUntilIdle()

        assertNull(
            "a past due date must be refused, not stored into state.dueAtMs",
            vm.state.value.dueAtMs,
        )
    }

    @Test
    fun `setDue with a past timestamp emits a user-facing info message`() = runTest(testDispatcher) {
        val vm = makeVm()
        advanceUntilIdle()

        val pastMs = System.currentTimeMillis() - 24 * 60 * 60 * 1000L // yesterday
        vm.setDue(pastMs)
        advanceUntilIdle()

        // This is the actual bug: the past date used to be accepted
        // silently and rendered verbatim into the outgoing message.
        // The fix must emit something DispatchSheet can show as a
        // Snackbar, mirroring CalendarGate's "That date is already
        // past" info message for the same underlying case.
        val msg = vm.infoChannel.tryReceive().getOrNull()
        assertNotNull("a refused past due date must emit an info message explaining why", msg)
        assertTrue(
            "info message must explain that the date is already past",
            msg!!.contains("past", ignoreCase = true),
        )
    }

    @Test
    fun `setDue with a future timestamp is accepted and emits no info message`() = runTest(testDispatcher) {
        val vm = makeVm()
        advanceUntilIdle()

        val futureMs = System.currentTimeMillis() + 24 * 60 * 60 * 1000L // tomorrow
        vm.setDue(futureMs)
        advanceUntilIdle()

        assertEquals(
            "a future due date must be stored as-is",
            futureMs,
            vm.state.value.dueAtMs,
        )
        assertNull(
            "accepting a valid due date must not emit an info message",
            vm.infoChannel.tryReceive().getOrNull(),
        )
    }

    @Test
    fun `setDue with null clears the due date without emitting an info message`() = runTest(testDispatcher) {
        val vm = makeVm()
        advanceUntilIdle()

        val futureMs = System.currentTimeMillis() + 24 * 60 * 60 * 1000L
        vm.setDue(futureMs)
        advanceUntilIdle()
        vm.infoChannel.tryReceive() // drain, not under test here

        vm.setDue(null)
        advanceUntilIdle()

        assertNull("clearing the due date (the DueChip trailing-icon action) must work", vm.state.value.dueAtMs)
        assertNull(
            "clearing the due date must not emit an info message",
            vm.infoChannel.tryReceive().getOrNull(),
        )
    }

    /**
     * Product decision (adversarial-QA audit + explicit follow-up
     * decision, not a mechanical bug): a fully-successful dispatch send
     * used to auto-mark the instruction DONE purely because the outbound
     * SMS/WhatsApp intent launched (`result.failed == 0 && result.sent > 0`).
     * That conflates "message launched" with "task actually completed" --
     * every other instruction in this app requires an explicit "Mark
     * done" action, and dispatch should be no different. [submit] no
     * longer calls [InstructionRepository.markDone] at all.
     */
    @Test
    fun `submit does not mark the instruction done even when the dispatch send fully succeeds`() = runTest(testDispatcher) {
        val instructionRepository = mockk<InstructionRepository>(relaxed = true)
        val personRepository = mockk<PersonRepository>(relaxed = true)
        every { personRepository.observeAll() } returns MutableStateFlow(emptyList<Person>()).asStateFlow()
        val deliveryService = mockk<DeliveryService>(relaxed = true)
        val vm = DispatchViewModel(instructionRepository, personRepository, deliveryService)
        advanceUntilIdle()

        val created = Instruction(
            id = "ins-1", personId = null, direction = Direction.OUTGOING, status = Status.OPEN,
            source = Source.TEXT, priority = Priority.NORMAL, title = "Bandobast update", rawText = "Bandobast update",
            dueAt = null, capturedAt = "2026-01-01T00:00:00Z", createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
        )
        coEvery {
            instructionRepository.createWithAudience(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns created
        // A fully successful send: every recipient reached, nothing failed.
        coEvery { deliveryService.dispatch(any(), any()) } returns DeliveryService.Result(recipients = 3, sent = 3, failed = 0)

        vm.setAudience(AudienceRef.ByAll(scope = "all", label = "Everyone"))
        advanceUntilIdle()

        var onDoneCalledWith: String? = null
        vm.submit(
            title = "Bandobast update", rawText = "Bandobast update",
            senderName = "SP Selvam", senderDesignation = "SP", senderDivision = "Thanjavur",
            onDone = { onDoneCalledWith = it },
        )
        advanceUntilIdle()

        assertEquals("onDone must still fire with the created instruction's id", "ins-1", onDoneCalledWith)
        coVerify(exactly = 0) { instructionRepository.markDone(any(), any()) }
    }
}
