package com.kaavalan.note.data.reminder

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.SavedStateHandle
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.kaavalan.note.data.instructions.RoomInstructionRepository
import com.kaavalan.note.data.instructions.Status
import com.kaavalan.note.data.instructions.toDomain
import com.kaavalan.note.data.local.AppDatabase
import com.kaavalan.note.data.local.TouchPersonOnActivity
import com.kaavalan.note.data.captures.Capture
import com.kaavalan.note.data.captures.CaptureMode
import com.kaavalan.note.data.captures.CaptureRepository
import com.kaavalan.note.data.person.PersonRepository
import com.kaavalan.note.data.tags.RoomTagRepository
import com.kaavalan.note.features.capture.CaptureViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the production reminder path from Room persistence through durable
 * scheduling, worker delivery, notification actions, snooze, and completion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReminderWorkflowEndToEndTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomInstructionRepository
    private lateinit var scheduler: WorkManagerReminderScheduler
    private lateinit var notifier: ReminderNotifier
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).build(),
        )
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomInstructionRepository(
            db = db,
            dao = db.instructionDao(),
            ftsDao = db.instructionFtsDao(),
            syncQueueDao = db.syncQueueDao(),
            touchOnActivity = TouchPersonOnActivity(db.personDao()),
            appScope = CoroutineScope(testDispatcher),
        )
        scheduler = WorkManagerReminderScheduler(context)
        notifier = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `saved reminder is scheduled delivered snoozed and completed`() = runTest(testDispatcher) {
        val reminderAtMs = System.currentTimeMillis() + 3_600_000L
        val captureRepository = mockk<CaptureRepository>()
        coEvery { captureRepository.create(any(), any()) } returns Capture(
            id = "capture-7",
            mode = CaptureMode.TEXT,
            rawText = "Review station diary",
            processed = false,
            createdAt = "2026-09-08T00:00:00Z",
        )
        val personRepository = mockk<PersonRepository>(relaxed = true)
        every { personRepository.observeAll() } returns
            kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        val tagRepository = mockk<RoomTagRepository>(relaxed = true)
        every { tagRepository.observeAll() } returns
            kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        val captureViewModel = CaptureViewModel(
            savedStateHandle = SavedStateHandle(),
            captureRepository = captureRepository,
            personRepository = personRepository,
            instructionRepository = repository,
            tagRepository = tagRepository,
            reminderScheduler = scheduler,
        )

        captureViewModel.openSheet()
        captureViewModel.onTextChanged("Review station diary")
        captureViewModel.onReminderChanged(reminderAtMs)
        captureViewModel.onSaveRaw()
        advanceUntilIdle()

        val instruction = repository.fetchAll().single()
        assertEquals(reminderAtMs, instruction.dueAtMs)
        coVerify(exactly = 1) {
            captureRepository.create(
                "Review station diary",
                CaptureMode.TEXT,
            )
        }

        val scheduled = waitForWork(WorkManagerReminderScheduler.workName(instruction.id))
        assertEquals(1, scheduled.size)
        assertTrue(scheduled.single().state == WorkInfo.State.ENQUEUED)

        val workerResult = reminderWorker(instruction.id, reminderAtMs).doWork()
        assertEquals(ListenableWorker.Result.success(), workerResult)

        verify(exactly = 1) { notifier.show(instruction.id, "Review station diary") }

        val handler = ReminderActionHandler(repository, scheduler, notifier)
        val snoozeStartedAt = reminderAtMs + 1_000L
        handler.handle(
            ReminderActionReceiver.ACTION_SNOOZE,
            instruction.id,
            nowMs = snoozeStartedAt,
        )
        val snoozedUntil = snoozeStartedAt + ReminderActionHandler.SNOOZE_DURATION_MS
        assertEquals(snoozedUntil, db.instructionDao().getById(instruction.id)!!.dueAtMs)
        verify(exactly = 1) { notifier.dismiss(instruction.id) }

        handler.handle(
            ReminderActionReceiver.ACTION_DONE,
            instruction.id,
            nowMs = snoozedUntil,
        )
        assertEquals(
            Status.DONE,
            db.instructionDao().getById(instruction.id)!!.toDomain().status,
        )
    }

    private fun reminderWorker(instructionId: String, reminderAtMs: Long): ReminderWorker =
        TestListenableWorkerBuilder<ReminderWorker>(context)
            .setInputData(
                workDataOf(
                    ReminderWorker.KEY_INSTRUCTION_ID to instructionId,
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
                        db.instructionDao(),
                        notifier,
                    )
                },
            )
            .build()

    private fun waitForWork(uniqueWorkName: String): List<WorkInfo> {
        repeat(200) {
            val infos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(uniqueWorkName)
                .get()
            if (infos.isNotEmpty()) return infos
            Thread.sleep(10L)
        }
        return emptyList()
    }
}
