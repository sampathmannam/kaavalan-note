package com.kaavalan.note.data.reminder

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kaavalan.note.data.instructions.Status
import com.kaavalan.note.data.instructions.toDomain
import com.kaavalan.note.data.local.InstructionDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** Delivers a reminder only when its instruction is still open and current. */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val instructionDao: InstructionDao,
    private val notifier: ReminderNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val instructionId = inputData.getString(KEY_INSTRUCTION_ID)
            ?: return Result.failure()
        val expectedReminderAtMs = inputData.getLong(KEY_REMINDER_AT_MS, NO_REMINDER)
        if (expectedReminderAtMs == NO_REMINDER) return Result.failure()

        val instruction = instructionDao.getById(instructionId)?.toDomain()
            ?: return Result.success()
        if (instruction.status in CLOSED_STATUSES) return Result.success()
        if (instruction.dueAtMs != expectedReminderAtMs) return Result.success()

        notifier.show(instruction.id, instruction.title)
        return Result.success()
    }

    @AssistedFactory
    interface Factory {
        fun create(appContext: Context, params: WorkerParameters): ReminderWorker
    }

    companion object {
        const val KEY_INSTRUCTION_ID = "instruction_id"
        const val KEY_REMINDER_AT_MS = "reminder_at_ms"
        private const val NO_REMINDER = Long.MIN_VALUE
        private val CLOSED_STATUSES = setOf(Status.DONE, Status.CARRIED_OVER, Status.DROPPED)
    }
}
