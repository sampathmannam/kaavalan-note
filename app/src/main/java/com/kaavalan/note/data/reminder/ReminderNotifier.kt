package com.kaavalan.note.data.reminder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kaavalan.note.MainActivity
import com.kaavalan.note.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Posts and dismisses private, actionable instruction reminders. */
@Singleton
class ReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun show(instructionId: String, instructionTitle: String): Boolean {
        ensureChannel()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_REMINDER
            putExtra(MainActivity.EXTRA_REMINDER_INSTRUCTION_ID, instructionId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            requestCode(instructionId, OPEN_REQUEST_SALT),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val doneIntent = ReminderActionReceiver.intent(
            context = context,
            action = ReminderActionReceiver.ACTION_DONE,
            instructionId = instructionId,
        )
        val snoozeIntent = ReminderActionReceiver.intent(
            context = context,
            action = ReminderActionReceiver.ACTION_SNOOZE,
            instructionId = instructionId,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_voice_notification)
            .setContentTitle(context.getString(R.string.reminder_notification_title))
            .setContentText(instructionTitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(instructionTitle))
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(
                0,
                context.getString(R.string.reminder_action_done),
                PendingIntent.getBroadcast(
                    context,
                    requestCode(instructionId, DONE_REQUEST_SALT),
                    doneIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                0,
                context.getString(R.string.reminder_action_snooze),
                PendingIntent.getBroadcast(
                    context,
                    requestCode(instructionId, SNOOZE_REQUEST_SALT),
                    snoozeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_voice_notification)
                    .setContentTitle(context.getString(R.string.reminder_notification_public_title))
                    .setContentText(context.getString(R.string.reminder_notification_public_body))
                    .build(),
            )
            .build()

        return runCatching {
            NotificationManagerCompat.from(context)
                .notify(notificationId(instructionId), notification)
            true
        }.getOrDefault(false)
    }

    fun dismiss(instructionId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(instructionId))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.reminder_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "instruction-reminders"
        private const val OPEN_REQUEST_SALT = 1
        private const val DONE_REQUEST_SALT = 2
        private const val SNOOZE_REQUEST_SALT = 3

        internal fun notificationId(instructionId: String): Int =
            instructionId.hashCode() and Int.MAX_VALUE

        private fun requestCode(instructionId: String, salt: Int): Int =
            (31 * instructionId.hashCode() + salt) and Int.MAX_VALUE
    }
}
