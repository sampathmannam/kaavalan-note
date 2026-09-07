package com.kaavalan.note.data.retention

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kaavalan.note.data.audit.AuditChainWriter
import com.kaavalan.note.data.local.AuditChainEventDao
import com.kaavalan.note.data.local.CaptureDao
import com.kaavalan.note.data.local.ImportantDateDao
import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.SyncQueueDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * v1.8.0 (PROD-READINESS-P2-#5): the retention worker.
 * Runs on a daily WorkManager schedule (separate from
 * the backup schedule). For each table in
 * [RetentionTable], deletes (or, for the audit chain,
 * redacts) rows whose `createdAt` is older than the
 * corresponding [RetentionPolicy] window.
 *
 * **Why redact the audit chain instead of deleting.**
 * The chain's value is its integrity — a future
 * forensic reader verifies that the chain is unbroken
 * to confirm "no one tampered with the local DB".
 * Deleting old rows would break the chain (the prevHash
 * of the next row would dangle). Redacting the JSON
 * payload but keeping the hash preserves the chain
 * integrity while erasing the actual historical
 * content after the legal retention window.
 *
 * **Soft-delete for instruction rows.** Instruction
 * rows are the user's active casework. The retention
 * worker does NOT delete them automatically — instead
 * it logs a [RetentionReport] row that the user can
 * review in Settings → Compliance and confirm-delete
 * from there. The v1.8.0 trade-off is "we don't
 * accidentally lose the user's casework because a
 * silent 7-year timer fired".
 *
 * **Re-enqueue.** The work request is a one-shot, not
 * periodic — WorkManagerInitializer schedules the
 * next run after each completion so the daily cadence
 * is correct without a periodic-policy clamp.
 */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val captureDao: CaptureDao,
    private val instructionDao: InstructionDao,
    private val importantDateDao: ImportantDateDao,
    private val auditDao: AuditChainEventDao,
    private val auditChainWriter: AuditChainWriter,
    private val syncQueueDao: SyncQueueDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val policy = RetentionPolicy.DEFAULT
        val now = System.currentTimeMillis()
        var deletedCaptures = 0
        var deletedDates = 0
        var redactedAudit = 0
        var clearedOutboxPayloads = 0

        // Captures: hard delete. Photos + voice audio
        // are the largest tables; deleting them is
        // the storage win the user expects. The
        // `createdAt` is an ISO-8601 string; we
        // convert the cutoff to ISO for the lex
        // compare (ISO strings sort in time order).
        runCatching {
            val cutoffMs = policy.redactBeforeMs(RetentionTable.CAPTURES, now)
            val cutoffIso = java.time.Instant.ofEpochMilli(cutoffMs).toString()
            deletedCaptures = captureDao.deleteOlderThan(cutoffIso)
        }

        // Important dates: hard delete.
        runCatching {
            val cutoffMs = policy.redactBeforeMs(RetentionTable.IMPORTANT_DATES, now)
            val cutoffIso = java.time.Instant.ofEpochMilli(cutoffMs).toString()
            deletedDates = importantDateDao.deleteOlderThan(cutoffIso)
        }

        // Audit chain: REDACT the JSON payload but
        // preserve the hash chain. createdAtMs is
        // already epoch millis so we compare
        // directly.
        runCatching {
            val cutoff = policy.redactBeforeMs(RetentionTable.AUDIT_CHAIN_EVENTS, now)
            redactedAudit = auditDao.redactOlderThan(cutoff, "{\"redacted\":true}")
        }

        // v2.2.1: the capture delete above is
        // `DELETE FROM captures`, which does not reach
        // sync_queue. Until v2.2.1 each capture also wrote
        // its `rawText` into `sync_queue.payloadJson` for a
        // drain that v2.0.0 deleted along with Supabase, so
        // nothing read those payloads and nothing removed
        // the rows. A capture deleted just above would keep
        // its text in that table indefinitely -- the
        // retention window would pass and the words would
        // still be there. New rows carry "{}"; this clears
        // the ones written by earlier builds.
        runCatching {
            clearedOutboxPayloads = syncQueueDao.clearCapturePayloads()
        }

        // The instruction rows are NOT auto-deleted;
        // a v2.x Compliance tab can present them for
        // user-confirmed deletion.

        // Write an audit row for the retention run
        // itself — the chain must show that retention
        // happened (so a forensic reader can verify
        // "yes, the worker ran on schedule").
        runCatching {
            auditChainWriter.append(
                tableName = "retention",
                rowId = now.toString(),
                kind = "RETENTION_RUN",
                payload = "{\"deletedCaptures\":$deletedCaptures," +
                    "\"deletedDates\":$deletedDates," +
                    "\"redactedAudit\":$redactedAudit," +
                    "\"clearedOutboxPayloads\":$clearedOutboxPayloads}",
            )
        }

        return Result.success(
            androidx.work.workDataOf(
                KEY_DELETED_CAPTURES to deletedCaptures,
                KEY_DELETED_DATES to deletedDates,
                KEY_REDACTED_AUDIT to redactedAudit,
                KEY_CLEARED_OUTBOX_PAYLOADS to clearedOutboxPayloads,
            )
        )
    }

    companion object {
        const val KEY_DELETED_CAPTURES = "retention.deletedCaptures"
        const val KEY_DELETED_DATES = "retention.deletedDates"
        const val KEY_REDACTED_AUDIT = "retention.redactedAudit"
        const val KEY_CLEARED_OUTBOX_PAYLOADS = "retention.clearedOutboxPayloads"
    }
}
