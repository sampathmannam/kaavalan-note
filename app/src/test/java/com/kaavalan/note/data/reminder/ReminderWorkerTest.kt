package com.kaavalan.note.data.reminder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.SyncStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReminderWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instructionDao = mockk<InstructionDao>()
    private val notifier = mockk<ReminderNotifier>(relaxed = true)
    private val reminderAtMs = 1_789_000_000_000L

    @Test
    fun `current open reminder posts a notification`() = runTest {
        coEvery { instructionDao.getById("instruction-7") } returns row()

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        verify(exactly = 1) { notifier.show("instruction-7", "Review station diary") }
    }

    @Test
    fun `stale replaced work does not post`() = runTest {
        coEvery { instructionDao.getById("instruction-7") } returns
            row(dueAtMs = reminderAtMs + 60_000L)

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        verify(exactly = 0) { notifier.show(any(), any()) }
    }

    @Test
    fun `closed instruction does not post`() = runTest {
        coEvery { instructionDao.getById("instruction-7") } returns row(status = "DONE")

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        verify(exactly = 0) { notifier.show(any(), any()) }
    }

    @Test
    fun `missing instruction finishes without retrying`() = runTest {
        coEvery { instructionDao.getById("instruction-7") } returns null

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { instructionDao.getById("instruction-7") }
    }

    private fun worker(): ReminderWorker =
        TestListenableWorkerBuilder<ReminderWorker>(context)
            .setInputData(
                workDataOf(
                    ReminderWorker.KEY_INSTRUCTION_ID to "instruction-7",
                    ReminderWorker.KEY_REMINDER_AT_MS to reminderAtMs,
                ),
            )
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = ReminderWorker(
                        appContext,
                        workerParameters,
                        instructionDao,
                        notifier,
                    )
                },
            )
            .build()

    private fun row(
        dueAtMs: Long = reminderAtMs,
        status: String = "OPEN",
    ): InstructionEntity = InstructionEntity(
        id = "instruction-7",
        personId = null,
        direction = "OUTGOING",
        status = status,
        source = "TEXT",
        priority = "NORMAL",
        title = "Review station diary",
        rawText = "Review station diary",
        dueAt = java.time.Instant.ofEpochMilli(dueAtMs).toString(),
        dueAtMs = dueAtMs,
        capturedAt = "2026-09-08T00:00:00Z",
        createdAt = "2026-09-08T00:00:00Z",
        updatedAt = "2026-09-08T00:00:00Z",
        syncStatus = SyncStatus.SYNCED,
    )
}
