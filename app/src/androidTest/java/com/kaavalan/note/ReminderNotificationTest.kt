package com.kaavalan.note

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.kaavalan.note.data.reminder.ReminderNotifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device-level verification of the platform notification payload. */
@RunWith(AndroidJUnit4::class)
class ReminderNotificationTest {

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val notifier = ReminderNotifier(context)

    @After
    fun tearDown() {
        notifier.dismiss(INSTRUCTION_ID)
    }

    @Test
    fun reminderNotification_isPrivateAndHasDoneAndSnoozeActions() {
        assertTrue(notifier.show(INSTRUCTION_ID, "Review station diary"))

        val notification = awaitActiveNotification()
        assertNotNull(notification)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification!!.visibility)
        assertNotNull(notification.publicVersion)
        assertEquals(2, notification.actions.size)
        assertEquals("Done", notification.actions[0].title.toString())
        assertEquals("Snooze 1 hour", notification.actions[1].title.toString())
    }

    private fun awaitActiveNotification(): Notification? {
        val manager = context.getSystemService(NotificationManager::class.java)
        repeat(40) {
            manager.activeNotifications
                .firstOrNull { it.id == ReminderNotifier.notificationId(INSTRUCTION_ID) }
                ?.notification
                ?.let { return it }
            SystemClock.sleep(50)
        }
        return null
    }

    private companion object {
        const val INSTRUCTION_ID = "reminder-notification-test"
    }
}
