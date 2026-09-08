package com.kaavalan.note.data.reminder

import com.kaavalan.note.data.instructions.InstructionRepository
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ReminderActionHandlerTest {

    private val repository = mockk<InstructionRepository>(relaxed = true)
    private val scheduler = mockk<ReminderScheduler>(relaxed = true)
    private val notifier = mockk<ReminderNotifier>(relaxed = true)
    private val handler = ReminderActionHandler(repository, scheduler, notifier)

    @Test
    fun `Done closes the instruction and cancels reminder delivery`() = runTest {
        val nowMs = 1_789_000_000_000L

        handler.handle(ReminderActionReceiver.ACTION_DONE, "instruction-7", nowMs)

        coVerify(exactly = 1) {
            repository.markDone(
                "instruction-7",
                java.time.Instant.ofEpochMilli(nowMs).toString(),
            )
        }
        verify(exactly = 1) { scheduler.cancel("instruction-7") }
        verify(exactly = 1) { notifier.dismiss("instruction-7") }
    }

    @Test
    fun `Snooze moves the reminder one hour and replaces its work`() = runTest {
        val nowMs = 1_789_000_000_000L
        val snoozedUntil = nowMs + ReminderActionHandler.SNOOZE_DURATION_MS

        handler.handle(ReminderActionReceiver.ACTION_SNOOZE, "instruction-7", nowMs)

        coVerify(exactly = 1) { repository.setDueChip("instruction-7", snoozedUntil) }
        verify(exactly = 1) { scheduler.schedule("instruction-7", snoozedUntil) }
        verify(exactly = 1) { notifier.dismiss("instruction-7") }
    }
}
