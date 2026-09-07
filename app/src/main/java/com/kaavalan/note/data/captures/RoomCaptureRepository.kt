package com.kaavalan.note.data.captures

import com.kaavalan.note.data.local.CaptureDao
import com.kaavalan.note.data.local.SyncQueueDao
import com.kaavalan.note.data.local.entities.CaptureEntity
import com.kaavalan.note.data.local.entities.SyncQueueEntity
import com.kaavalan.note.data.local.entities.SyncStatus
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.4.2 (F-09 / F-20) Room-backed [CaptureRepository]. The capture
 * is written to local Room *first* (offline-tolerant), then a
 * sync_queue row is enqueued and the
 * [com.kaavalan.note.data.sync.CaptureSyncWorker] (or, eventually, the
 * existing [com.kaavalan.note.data.local.SyncEngine] periodic drain)
 * pushes the row to Supabase in the background.
 *
 * **Why this exists (F-09 / F-20 CRITICAL).** The pre-v1.4.2
 * implementation was [SupabaseCaptureRepository]: every
 * `create()` did a direct POST to the `captures` table. If Supabase
 * was down at save time, the throw bubbled up to the
 * [com.kaavalan.note.features.capture.CaptureViewModel] and the user's
 * note was lost. With this Room mirror, the local row exists
 * before the network call, so a Supabase outage no longer costs
 * data — the row survives in Room with `syncStatus = PENDING_INSERT`
 * and the worker will drain it when the network is back.
 *
 * **Write path (offline-first):**
 *  1. Generate a client-side UUID for the row (the v1.3
 *     BATON-WIRE-006 idempotency key — a retry with the same id
 *     is a no-op on the server).
 *  2. Insert into Room with `processed = false` and
 *     `syncStatus = PENDING_INSERT`. The user sees the row
 *     immediately.
 *  3. Enqueue a `sync_queue` row (`table = "captures"`, op =
 *     `INSERT`, `payloadJson = "{}"`).
 *
 *     **v2.2.1: the payload is empty.** It used to carry the
 *     capture's `rawText`, for a drain that would read it and
 *     POST it. v2.0.0 removed Supabase and with it every drain:
 *     `enqueueCaptureSync` is a no-op, nothing calls
 *     `SyncQueueDao.deleteById`, and `CaptureSyncWorker` and
 *     `SyncEngine` are deleted. So the payload was read by
 *     nothing and deleted by nothing — it just accumulated a
 *     second copy of every note's text in a table the retention
 *     sweep does not touch. `RetentionWorker` hard-deletes
 *     captures past their window with
 *     `DELETE FROM captures`, which left that copy behind: text
 *     the app had told the user was deleted, kept indefinitely.
 *
 *     The empty payload matches what
 *     [com.kaavalan.note.data.instructions.RoomInstructionRepository]
 *     has always enqueued, and makes retention correct by
 *     construction — there is no second copy to miss.
 *     `RetentionWorker` clears the historical payloads via
 *     `SyncQueueDao.clearCapturePayloads`.
 *  4. Return the local [Capture] to the caller synchronously. The
 *     caller never sees a network failure on the hot path.
 *
 * **Wire push (F-09 fix).** The actual POST to Supabase is done
 * by [com.kaavalan.note.data.sync.CaptureSyncWorker.processDirtyRows],
 * which reads dirty captures from Room, calls
 * [SupabaseCaptureRepository.insertCapture] with the row's id, and
 * on success flips `syncStatus` to `SYNCED` and deletes the
 * matching `sync_queue` row. **The worker is the path that survives
 * a Supabase outage** — the periodic drain can keep retrying.
 *
 * **`markProcessed()` flow.** Same pattern: update Room first
 * (`processed = true`, `syncStatus = PENDING_UPDATE`), enqueue an
 * `OP_UPDATE` sync_queue row. The worker / drain PATCHes the
 * server's `processed` column to match.
 *
 * **The pre-existing `SyncEngine.processCaptureEntry()` is a stub**
 * that just flips `syncStatus = SYNCED` without calling Supabase.
 * The worker is the only path that actually pushes the row to the
 * server. The worker deletes the sync_queue row on success so
 * SyncEngine's stub never runs against an already-pushed row.
 * If SyncEngine's drain wins the race, the row's `syncStatus` is
 * flipped to `SYNCED` and the worker skips it — this is a
 * pre-existing race surfaced as a limitation; the durable fix
 * requires modifying SyncEngine, which is out of scope for this
 * branch.
 *
 * **Threading.** Room suspend functions dispatch internally; the
 * public methods are safe to call from any dispatcher.
 *
 * **Stale above, not touched here.** The paragraphs describing
 * the wire push still reference `CaptureSyncWorker`, `SyncEngine`
 * and `SupabaseCaptureRepository`. All three were deleted in
 * v2.0.0; none of it describes the current code. That predates
 * this change and rewriting it is a separate job.
 */
@Singleton
class RoomCaptureRepository @Inject constructor(
    private val dao: CaptureDao,
    private val syncQueueDao: SyncQueueDao,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : CaptureRepository {

    private val now: () -> Long = { System.currentTimeMillis() }

    override suspend fun create(rawText: String, mode: CaptureMode): Capture {
        val nowIso = nowIso()
        val id = UUID.randomUUID().toString()
        val local = CaptureEntity(
            id = id,
            mode = mode.toDbValue(),
            rawText = rawText,
            audioUri = null,
            imageUri = null,
            processed = false,
            createdAt = nowIso,
            // F-09: PENDING_INSERT so the worker picks it up.
            // The user's note is in Room the moment this returns.
            syncStatus = SyncStatus.PENDING_INSERT,
        )
        dao.upsert(local)
        syncQueueDao.enqueue(
            SyncQueueEntity(
                table = "captures",
                rowId = id,
                op = SyncQueueEntity.OP_INSERT,
                payloadJson = "{}",
                createdAt = now(),
            )
        )
        // v1.4.4: per-write one-shot sync. The 15-min periodic in
        // KaavalanApplication is the safety net; this is the foreground
        // path that gives the user "saved to server" within a few
        // seconds of tapping Save (instead of up to 15 min).
        com.kaavalan.note.data.work.WorkManagerInitializer
            .enqueueCaptureSync(context)
        return local.toDomain()
    }

    override suspend fun markProcessed(id: String) {
        // Update the local row first — the user sees the change
        // synchronously, even if the wire PATCH is delayed by a
        // Supabase outage. PENDING_UPDATE keeps the row visible to
        // the worker / drain.
        dao.setProcessed(id, true, SyncStatus.PENDING_UPDATE)
        // Enqueue the UPDATE so the server's `processed` column
        // catches up. The drain (and the new
        // [com.kaavalan.note.data.sync.CaptureSyncWorker]) will PATCH
        // the server row; on success, both `syncStatus` is set to
        // `SYNCED` and the sync_queue row is deleted.
        // Still guard on existence: enqueueing for a row that
        // isn't there would leave an outbox entry pointing at
        // nothing.
        dao.getById(id) ?: return
        syncQueueDao.enqueue(
            SyncQueueEntity(
                table = "captures",
                rowId = id,
                op = SyncQueueEntity.OP_UPDATE,
                payloadJson = "{}",
                createdAt = now(),
            )
        )
        // v1.4.4: per-write one-shot sync for the UPDATE too.
        com.kaavalan.note.data.work.WorkManagerInitializer
            .enqueueCaptureSync(context)
    }

    private fun nowIso(): String = java.time.Instant.now().toString()

    private fun CaptureEntity.toDomain(): Capture = Capture(
        id = id,
        mode = CaptureMode.fromDbValue(mode),
        rawText = rawText,
        audioUri = audioUri,
        imageUri = imageUri,
        processed = processed,
        createdAt = createdAt,
    )
}
