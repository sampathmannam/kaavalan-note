package com.kaavalan.note.data.reminder

import com.kaavalan.note.data.instructions.InstructionRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps the persisted reminder value and its scheduled work in sync. */
@Singleton
class ReminderManager @Inject constructor(
    private val instructionRepository: InstructionRepository,
    private val scheduler: ReminderScheduler,
    private val notifier: ReminderNotifier,
) {
    suspend fun update(instructionId: String, reminderAtMs: Long?) {
        instructionRepository.setDueChip(instructionId, reminderAtMs)
        scheduleSaved(instructionId, reminderAtMs)
    }

    /** Used after an atomic journal + reminder write. Does not change the persisted record. */
    fun scheduleSaved(instructionId: String, reminderAtMs: Long?) {
        if (reminderAtMs == null) {
            scheduler.cancel(instructionId)
            notifier.dismiss(instructionId)
        } else {
            scheduler.schedule(instructionId, reminderAtMs)
        }
    }

    fun cancelDelivery(instructionId: String) {
        scheduler.cancel(instructionId)
        notifier.dismiss(instructionId)
    }
}
