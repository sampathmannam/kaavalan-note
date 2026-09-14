package com.kaavalan.note.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kaavalan.note.data.local.InstructionDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Re-arms AlarmManager reminders after reboot, app replacement, or an exact-access grant. */
@AndroidEntryPoint
class ReminderRescheduleReceiver : BroadcastReceiver() {

    @Inject lateinit var instructionDao: InstructionDao
    @Inject lateinit var scheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                instructionDao.snapshotOpenReminders().forEach { row ->
                    row.dueAtMs?.let { scheduler.schedule(row.id, it) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // WorkManager retains its own persisted schedules across reboot. A
                // reschedule failure therefore degrades precision, not delivery.
                Log.e(TAG, "Reminder re-arm failed (${failure.javaClass.simpleName})")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ReminderReschedule"
        // AlarmManager's field was added in API 31, but the broadcast action
        // is a stable string and may safely live in a min-26 receiver. Keeping
        // the value local prevents the class verifier/lint from treating the
        // newer SDK field as an unguarded API reference.
        const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_EXACT_ALARM_PERMISSION_CHANGED,
        )
    }
}
