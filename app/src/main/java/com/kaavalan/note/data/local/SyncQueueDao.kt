package com.kaavalan.note.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kaavalan.note.data.local.entities.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncQueueDao {

    /**
     * v1.2.2 (F-HIGH-07): drain order is FIFO by id, with
     * backoff. Entries whose `nextAttemptAt` is in the future
     * (i.e. the previous attempt failed and the backoff window
     * hasn't expired) are skipped. Permanently-failed entries
     * (where `lastError` starts with `PERMANENT_FAILURE:`) are
     * also skipped — they only re-appear after
     * [resetPermanentlyFailed] is called.
     */
    @Query(
        "SELECT * FROM sync_queue " +
            "WHERE nextAttemptAt <= :now " +
            "AND (lastError IS NULL OR lastError NOT LIKE 'PERMANENT_FAILURE:%') " +
            "ORDER BY id ASC",
    )
    suspend fun snapshotReady(now: Long): List<SyncQueueEntity>

    /**
     * Pre-v1.2.2 callers (e.g. instrumentation tests that don't
     * care about backoff) can still read the full queue. Production
     * code uses [snapshotReady].
     */
    @Query("SELECT * FROM sync_queue ORDER BY id ASC")
    suspend fun snapshot(): List<SyncQueueEntity>

    @Query("SELECT * FROM sync_queue ORDER BY id ASC")
    fun observe(): Flow<List<SyncQueueEntity>>

    @Query("SELECT * FROM sync_queue WHERE `table` = :table AND rowId = :rowId AND op = :op LIMIT 1")
    suspend fun findPending(table: String, rowId: String, op: String): SyncQueueEntity?

    /**
     * v1.4.2 (DATA-FINDING-04): on conflict REPLACE so a
     * double-tap of the same UI action (e.g. `markDone`) that
     * enqueues two `UPDATE` rows for the same `(table, rowId)`
     * collapses to a single row holding the latest payload.
     * The unique index on `(op, table, rowId)` (declared on
     * [SyncQueueEntity]) is what raises the conflict. The new
     * row is assigned a fresh auto-generated `id`, so any
     * external references to the old id (e.g. an in-flight
     * `recordFailureWithBackoff` from the drain) become stale —
     * the new row's failure / backoff counters start at zero,
     * which is the intended "fresh attempt" semantics.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(entry: SyncQueueEntity): Long

    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * v2.2.1: blank the stored payloads on `captures` rows.
     * Returns the number of rows changed.
     *
     * Until v2.2.1 [com.kaavalan.note.data.captures.RoomCaptureRepository]
     * wrote each capture's `rawText` into `payloadJson`, for a
     * drain to read and POST. v2.0.0 removed Supabase and every
     * drain with it, so nothing ever read those payloads and
     * nothing ever deleted the rows. The effect was a second copy
     * of every note's text in a table `RetentionWorker` does not
     * touch: its `DELETE FROM captures` cleared the capture and
     * left the copy, so text the app had reported as deleted
     * stayed in the database indefinitely.
     *
     * New rows carry `"{}"`. This clears the ones already written
     * on devices that have been running an earlier build, which is
     * the only way that historical text goes away.
     *
     * Same shape as the audit chain's redact-in-place
     * ([AuditChainEventDao.redactOlderThan]): the row stays, the
     * content goes. Deleting the rows outright would work too, but
     * the outbox is forward-compat scaffolding for a future cloud
     * sync and the row identity is the part worth keeping.
     */
    @Query(
        "UPDATE sync_queue SET payloadJson = '{}' " +
            "WHERE `table` = 'captures' AND payloadJson != '{}'",
    )
    suspend fun clearCapturePayloads(): Int

    /**
     * v1.8.0 (PROD-READINESS-P2-P1-#4): trim the
     * outbox to [maxSize] rows. Oldest-wins eviction:
     * rows with the highest `id` are deleted (since
     * `id` is `AUTOINCREMENT` and the order is
     * insertion order, the highest ids are the
     * newest). Returns the number of rows deleted.
     *
     * Used by the enqueue-with-cap path to keep the
     * on-disk outbox bounded; without it, an
     * indefinitely-offline device (or a pilot
     * station that's been on a flaky network for
     * a week) would accumulate millions of rows.
     *
     * **Why delete vs mark-deleted.** The outbox
     * is a write queue — the rows it holds are
     * not user data, they're "the next write to
     * attempt". Deleting the oldest "write
     * attempt" rows is correct: the user action
     * that produced them already happened locally
     * (the in-Room `syncStatus` is
     * `PENDING_INSERT`), so the worst case for a
     * dropped row is "the local change never
     * reaches the server" — not "the local change
     * is lost". A future v2.x can re-architect
     * with a "high-water mark" that pauses
     * enqueueing instead of dropping, but the
     * v1.8.0 trade-off is "drop, never block the
     * user".
     */
    @Query(
        "DELETE FROM sync_queue WHERE id IN (" +
            "SELECT id FROM sync_queue ORDER BY id DESC LIMIT -1 OFFSET :maxSize" +
            ")",
    )
    suspend fun trimToLimit(maxSize: Int): Int

    /**
     * v1.2.2 (F-HIGH-07): backoff-aware failure record. The
     * `nextAttemptAt` is set to `now + backoffMs` so the drain
     * skips the entry on the next pass. The exponential backoff
     * is `1s * 2^attempts` capped at 5 minutes — a transient
     * 30s outage won't trigger a tight retry loop, but a
     * 5-minute cap means the user isn't waiting forever.
     */
    @Query(
        "UPDATE sync_queue SET attempts = attempts + 1, lastError = :error, " +
            "nextAttemptAt = :nextAttemptAt WHERE id = :id",
    )
    suspend fun recordFailureWithBackoff(id: Long, error: String, nextAttemptAt: Long)

    /**
     * Pre-v1.2.2 callers: legacy `recordFailure` (no backoff).
     * Kept for test backwards compat; production should use
     * [recordFailureWithBackoff] via [SyncEngine].
     */
    @Query("UPDATE sync_queue SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun recordFailure(id: Long, error: String)

    /**
     * v1.2.2 (F-HIGH-07): mark a row as permanently failed. The
     * drain skips it on every future pass (the `lastError` LIKE
     * `PERMANENT_FAILURE:%` check in [snapshotReady]). The user
     * can call [resetPermanentlyFailed] from a "Retry stuck
     * entries" action in Settings to put them back in rotation.
     *
     * Also bumps `attempts` to [attempts] (the final count from
     * the engine) so the UI / debug surfaces can read the actual
     * failure count without joining against [snapshot].
     */
    @Query(
        "UPDATE sync_queue SET attempts = :attempts, lastError = :error, " +
            "nextAttemptAt = 0 WHERE id = :id",
    )
    suspend fun markPermanentlyFailed(id: Long, attempts: Int, error: String)

    /**
     * v1.2.2 (F-HIGH-07): reset all `PERMANENT_FAILURE:*` rows
     * so they can be tried again. Called from Settings →
     * "Retry stuck outbox entries". The next drain re-processes
     * them with `attempts = 0` so the backoff clock starts over.
     */
    @Query(
        "UPDATE sync_queue SET attempts = 0, lastError = NULL, nextAttemptAt = 0 " +
            "WHERE lastError LIKE 'PERMANENT_FAILURE:%'",
    )
    suspend fun resetPermanentlyFailed(): Int

    /**
     * v1.2.4 (F-HIGH-08): count of `PERMANENT_FAILURE:*` rows.
     * Used by the SettingsViewModel to surface "N stuck outbox
     * rows" in the UI. The count is also the input to the
     * "Retry stuck entries" action.
     */
    @Query(
        "SELECT COUNT(*) FROM sync_queue WHERE lastError LIKE 'PERMANENT_FAILURE:%'",
    )
    fun observeStuckCount(): Flow<Int>

    /**
     * v1.2.4: synchronous count, for the SettingsViewModel's
     * `retryStuck()` action — it shows the count on the
     * confirmation snackbar ("Reset 3 stuck entries").
     */
    @Query(
        "SELECT COUNT(*) FROM sync_queue WHERE lastError LIKE 'PERMANENT_FAILURE:%'",
    )
    suspend fun stuckCount(): Int
}
