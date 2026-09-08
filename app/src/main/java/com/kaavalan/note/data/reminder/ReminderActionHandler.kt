package com.kaavalan.note.data.reminder

import com.kaavalan.note.data.instructions.InstructionRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Applies notification actions through the same repository used by the UI. */
@Singleton
class ReminderActionHandler @Inject constructor(
    private val instructionRepository: InstructionRepository,
    private val scheduler: ReminderScheduler,
    private val notifier: ReminderNotifier,
) {
    suspend fun handle(action: String, instructionId: String, nowMs: Long = System.currentTimeMillis()) {
        when (action) {
            ReminderActionReceiver.ACTION_DONE -> {
                instructionRepository.markDone(instructionId, Instant.ofEpochMilli(nowMs).toString())
                scheduler.cancel(instructionId)
                notifier.dismiss(instructionId)
            }

            ReminderActionReceiver.ACTION_SNOOZE -> {
                val snoozedUntil = nowMs + SNOOZE_DURATION_MS
                instructionRepository.setDueChip(instructionId, snoozedUntil)
                scheduler.schedule(instructionId, snoozedUntil)
                notifier.dismiss(instructionId)
            }
        }
    }

    companion object {
        const val SNOOZE_DURATION_MS = 60L * 60L * 1000L
    }
}
