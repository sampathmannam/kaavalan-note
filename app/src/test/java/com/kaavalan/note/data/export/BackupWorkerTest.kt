package com.kaavalan.note.data.export

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupWorkerTest {

    private lateinit var context: Context
    private lateinit var backupManager: BackupManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        backupManager = mockk()
    }

    @Test
    fun `empty but valid first-launch backup succeeds`() = runTest {
        coEvery { backupManager.backup() } returns File(context.cacheDir, "empty-backup")

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
    }

    @Test
    fun `ordinary backup failure retries`() = runTest {
        coEvery { backupManager.backup() } throws IllegalStateException("disk unavailable")

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
    }

    @Test
    fun `backup cancellation remains cooperative`() = runTest {
        coEvery { backupManager.backup() } throws CancellationException("worker stopped")
        var cancellationObserved = false

        try {
            worker().doWork()
        } catch (_: CancellationException) {
            cancellationObserved = true
        }

        assertTrue("cancellation must not be converted to retry", cancellationObserved)
    }

    private fun worker(): BackupWorker =
        TestListenableWorkerBuilder<BackupWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = BackupWorker(
                        appContext = appContext,
                        params = workerParameters,
                        backupManager = backupManager,
                    )
                },
            )
            .build()
}
