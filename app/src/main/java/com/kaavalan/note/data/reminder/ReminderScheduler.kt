package com.kaavalan.note.data.reminder

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import com.kaavalan.note.data.work.WorkManagerInitializer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules one durable reminder per instruction.
 *
 * WorkManager is intentional here: reminder work survives process death and
 * device restarts, while unique work makes changing a reminder replace the old
 * schedule instead of creating duplicate notifications.
 */
interface ReminderScheduler {
    fun schedule(instructionId: String, reminderAtMs: Long)
    fun cancel(instructionId: String)
}

@Singleton
class WorkManagerReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderScheduler {

    override fun schedule(instructionId: String, reminderAtMs: Long) {
        val delayMs = (reminderAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    ReminderWorker.KEY_INSTRUCTION_ID to instructionId,
                    ReminderWorker.KEY_REMINDER_AT_MS to reminderAtMs,
                ),
            )
            .build()
        WorkManagerInitializer.get(context).enqueueUniqueWork(
            workName(instructionId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override fun cancel(instructionId: String) {
        WorkManagerInitializer.get(context).cancelUniqueWork(workName(instructionId))
    }

    companion object {
        internal fun workName(instructionId: String): String =
            "kaavalan-note-reminder-$instructionId"
    }
}
