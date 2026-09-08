package com.kaavalan.note.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Receives Done and Snooze actions from an instruction reminder. */
@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {

    @Inject lateinit var actionHandler: ReminderActionHandler

    override fun onReceive(context: Context, intent: Intent) {
        val instructionId = intent.getStringExtra(EXTRA_INSTRUCTION_ID) ?: return
        val action = intent.action ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                actionHandler.handle(action, instructionId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_DONE = "com.kaavalan.note.action.REMINDER_DONE"
        const val ACTION_SNOOZE = "com.kaavalan.note.action.REMINDER_SNOOZE"
        const val EXTRA_INSTRUCTION_ID = "instruction_id"

        fun intent(context: Context, action: String, instructionId: String): Intent =
            Intent(context, ReminderActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_INSTRUCTION_ID, instructionId)
            }
    }
}
