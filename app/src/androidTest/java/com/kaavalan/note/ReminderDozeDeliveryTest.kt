package com.kaavalan.note

import android.app.AlarmManager
import android.os.SystemClock
import androidx.work.WorkManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.SyncStatus
import com.kaavalan.note.data.reminder.WorkManagerReminderScheduler
import com.kaavalan.note.debug.WorkspaceTestEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Sets up the reminder contract; scripts/qa-reminder-doze.sh verifies delivery out-of-process. */
@RunWith(AndroidJUnit4::class)
class ReminderDozeDeliveryTest {

    @get:Rule
    val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var dao: InstructionDao
    private lateinit var scheduler: WorkManagerReminderScheduler
    private val hostDozeProbe: Boolean
        get() = InstrumentationRegistry.getArguments().getString("hostDozeProbe") == "true"

    @Before
    fun setUp() {
        assumeTrue(
            "Run the out-of-process Doze probe through scripts/qa-reminder-doze.sh",
            hostDozeProbe,
        )
        check(
            context.packageName.endsWith(".debug.physicalqa") ||
                context.packageName.endsWith(".debug.officer"),
        ) { "Doze QA must run against an isolated debug application ID" }
        val graph = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WorkspaceTestEntryPoint::class.java,
        )
        dao = graph.instructions()
        scheduler = WorkManagerReminderScheduler(context)
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
    }

    @After
    fun tearDown() {
        if (!hostDozeProbe || !::scheduler.isInitialized) return
        // The host probe owns cleanup because it must inspect the notification
        // after this instrumentation process has exited.
    }

    @Test
    fun reminderHasExactAlarmAndDurableFallback() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        repeat(30) {
            if (alarmManager.canScheduleExactAlarms()) return@repeat
            SystemClock.sleep(100)
        }
        assertTrue("Exact-alarm app-op was not granted to the isolated QA package", alarmManager.canScheduleExactAlarms())

        val triggerAtMs = System.currentTimeMillis() + if (hostDozeProbe) 20_000L else 3_600_000L
        runBlocking { dao.upsert(reminderRow(triggerAtMs)) }
        scheduler.schedule(INSTRUCTION_ID, triggerAtMs)
        assertTrue(
            WorkManagerReminderScheduler.alarmPendingIntent(
                context,
                INSTRUCTION_ID,
                reminderAtMs = null,
                noCreate = true,
            ) != null,
        )
        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerReminderScheduler.workName(INSTRUCTION_ID))
            .get()
        assertTrue("Durable WorkManager fallback was not queued", work.isNotEmpty())
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun reminderRow(reminderAtMs: Long): InstructionEntity {
        val now = Instant.now().toString()
        return InstructionEntity(
            id = INSTRUCTION_ID,
            personId = null,
            direction = "OUTGOING",
            status = "OPEN",
            source = "TEXT",
            priority = "NORMAL",
            title = "Doze delivery check",
            rawText = "Doze delivery check",
            dueAt = Instant.ofEpochMilli(reminderAtMs).toString(),
            dueAtMs = reminderAtMs,
            capturedAt = now,
            createdAt = now,
            updatedAt = now,
            syncStatus = SyncStatus.SYNCED,
        )
    }

    private companion object {
        const val INSTRUCTION_ID = "reminder-doze-delivery-test"
    }
}
