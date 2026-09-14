package com.kaavalan.note.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kaavalan.note.data.instructions.Status
import com.kaavalan.note.data.instructions.toDomain
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.work.WorkManagerInitializer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Delivers an AlarmManager wake-up after re-validating the encrypted Room row. */
@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var instructionDao: InstructionDao
    @Inject lateinit var notifier: ReminderNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val instructionId = intent.getStringExtra(EXTRA_INSTRUCTION_ID) ?: return
        val expectedAtMs = intent.getLongExtra(EXTRA_REMINDER_AT_MS, NO_REMINDER)
        if (expectedAtMs == NO_REMINDER) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val instruction = instructionDao.getById(instructionId)?.toDomain()
                val stillCurrent = instruction != null &&
                    instruction.status !in CLOSED_STATUSES &&
                    instruction.dueAtMs == expectedAtMs
                if (stillCurrent && notifier.show(instructionId, instruction!!.title)) {
                    // Exact/inexact alarm won the race. Remove the later WorkManager
                    // backstop so it cannot refresh the same notification.
                    WorkManagerInitializer.get(context)
                        .cancelUniqueWork(WorkManagerReminderScheduler.workName(instructionId))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // The WorkManager request is deliberately left queued as recovery.
                Log.e(TAG, "Alarm delivery failed (${failure.javaClass.simpleName}); fallback retained")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReminderAlarm"
        const val EXTRA_INSTRUCTION_ID = "instruction_id"
        const val EXTRA_REMINDER_AT_MS = "reminder_at_ms"
        private const val NO_REMINDER = Long.MIN_VALUE
        private val CLOSED_STATUSES = setOf(Status.DONE, Status.CARRIED_OVER, Status.DROPPED)
    }
}
