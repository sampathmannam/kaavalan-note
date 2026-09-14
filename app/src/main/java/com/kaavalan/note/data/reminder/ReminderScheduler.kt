package com.kaavalan.note.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
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
 * Every reminder gets two coordinated delivery paths:
 *
 *  - AlarmManager wakes the app in Doze. It is exact when the user has granted
 *    Alarms & reminders access, and otherwise uses the platform's
 *    allow-while-idle inexact fallback.
 *  - WorkManager is the durable backstop across process death, force-stop
 *    recovery and OEM alarm quirks.
 *
 * Both paths validate the persisted timestamp before posting, and the
 * notification uses a stable ID with only-alert-once semantics, so a race
 * cannot produce two visible or audible reminders.
 */
interface ReminderScheduler {
    fun schedule(instructionId: String, reminderAtMs: Long)
    fun cancel(instructionId: String)
}

@Singleton
class WorkManagerReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderScheduler {

    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(instructionId: String, reminderAtMs: Long) {
        scheduleWorkManagerFallback(instructionId, reminderAtMs)
        runCatching { scheduleAlarm(instructionId, reminderAtMs) }
            .onFailure { failure ->
                // WorkManager remains queued as the durable fallback. Do not let an
                // OEM AlarmManager failure make saving the note fail.
                Log.e(TAG, "Alarm scheduling failed (${failure.javaClass.simpleName}); fallback retained")
            }
    }

    private fun scheduleWorkManagerFallback(instructionId: String, reminderAtMs: Long) {
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

    private fun scheduleAlarm(instructionId: String, reminderAtMs: Long) {
        val triggerAtMs = reminderAtMs.coerceAtLeast(System.currentTimeMillis() + 1L)
        val operation = requireNotNull(alarmPendingIntent(context, instructionId, reminderAtMs))
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                alarmManager.canScheduleExactAlarms() -> {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    operation,
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                // Exact access is denied by default on modern fresh installs. This
                // still wakes in Doze and is substantially more reliable than a
                // WorkManager delay; the UI offers the exact-access settings flow.
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    operation,
                )
            }
            else -> {
                @Suppress("DEPRECATION")
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, operation)
            }
        }
    }

    override fun cancel(instructionId: String) {
        WorkManagerInitializer.get(context).cancelUniqueWork(workName(instructionId))
        alarmPendingIntent(context, instructionId, reminderAtMs = null, noCreate = true)
            ?.let { operation ->
                alarmManager.cancel(operation)
                operation.cancel()
            }
    }

    companion object {
        private const val TAG = "ReminderScheduler"
        private const val ACTION_DELIVER = "com.kaavalan.note.action.DELIVER_REMINDER"

        internal fun workName(instructionId: String): String =
            "kaavalan-note-reminder-$instructionId"

        internal fun alarmPendingIntent(
            context: Context,
            instructionId: String,
            reminderAtMs: Long?,
            noCreate: Boolean = false,
        ): PendingIntent? {
            val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ACTION_DELIVER
                data = Uri.Builder()
                    .scheme("kaavalan-reminder")
                    .authority("instruction")
                    .appendPath(instructionId)
                    .build()
                reminderAtMs?.let {
                    putExtra(ReminderAlarmReceiver.EXTRA_INSTRUCTION_ID, instructionId)
                    putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_AT_MS, it)
                }
            }
            val lookupFlag = if (noCreate) PendingIntent.FLAG_NO_CREATE else PendingIntent.FLAG_UPDATE_CURRENT
            return PendingIntent.getBroadcast(
                context,
                instructionId.hashCode() and Int.MAX_VALUE,
                intent,
                lookupFlag or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
