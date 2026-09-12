package com.kaavalan.note.data.instructions

import com.kaavalan.note.data.local.InstructionDao
import com.kaavalan.note.data.local.InstructionFtsDao
import com.kaavalan.note.data.local.SyncQueueDao
import com.kaavalan.note.data.local.TouchPersonOnActivity
import com.kaavalan.note.data.local.entities.InstructionEntity
import com.kaavalan.note.data.local.entities.InstructionFtsEntity
import com.kaavalan.note.data.local.entities.SyncQueueEntity
import com.kaavalan.note.data.local.entities.SyncStatus
import com.kaavalan.note.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.room.withTransaction
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M3-T5 + v1.1: local mirror for the [InstructionRepository] contract.
 * The DAO is the source of truth for the People-list badge
 * (`observeOpenCountByPerson`) and the PersonDetailScreen timeline
 * (`observeForPerson`).
 *
 * **v1.5.1 (VAULT-005 fix):** this class now implements
 * [InstructionRepository] end-to-end. v1.5.0 vault mode binds the
 * `InstructionRepository` interface to this class (not the
 * Supabase one), so every capture-and-save flow lands in the
 * local SQLCipher Room DB. The `SupabaseInstructionRepository` is
 * still wired in (as a constructor dep of the sync engine and of
 * the optional `refreshFromNetwork` call) so a future Settings
 * toggle can flip the binding back to cloud sync without a
 * refactor.
 *
 * Each write goes through Room (PENDING_INSERT / PENDING_UPDATE)
 * + the [SyncEngine] outbox. In vault mode the outbox is never
 * drained (the periodic workers are not scheduled), so PENDING
 * rows sit forever — that's fine, they're the durable source of
 * truth locally.
 *
 * `fetchAll()` returns the local mirror. In vault mode this IS the
 * full dataset. In a future cloud mode it would be a snapshot of
 * the locally-cached rows (the post-`refreshFromNetwork` state).
 *
 * **v2.0 (Tier 1.3):** every write also upserts the FTS4 row
 * (free FTS4 entity, no `contentEntity` link).
 *
 * **v1.9.8 (PROD-READINESS-P0-#2 — production blocker fix):**
 * the [create] path now wraps all four writes (main row, FTS row,
 * sync outbox entry, last-interaction touch) in a single Room
 * `withTransaction { ... }` block. The pre-fix version wrote them
 * sequentially across four coroutine steps; a process death or
 * JVM crash between the main-table insert and the FTS upsert left
 * the FTS index pointing at a non-existent rowid (search broken
 * until the next reseed). A crash between the FTS upsert and the
 * sync-queue enqueue left a row that the user could see in the
 * app but that would never reach Supabase. A crash between the
 * sync-queue enqueue and the last-interaction touch left the
 * decay view showing the user as "haven't touched in 30+ days"
 * for a person they had just saved an instruction about.
 *
 * Trade-off: wrapping in a transaction means the FTS row is
 * written AFTER the main row inside the same atomic block. The
 * pre-fix comment worried about "the FTS row never references a
 * missing main row" — that property is still guaranteed by
 * Room: the FTS DAO reads the main row's rowid via
 * [InstructionFtsDao.maxInstructionRowid] inside the same
 * transaction, so a partial read is impossible. Crash recovery
 * is now atomic: either all four writes land, or none do, and
 * the user sees a consistent local state.
 */
@Singleton
open class RoomInstructionRepository @Inject constructor(
    private val db: com.kaavalan.note.data.local.AppDatabase,
    private val dao: InstructionDao,
    private val ftsDao: InstructionFtsDao,
    private val syncQueueDao: SyncQueueDao,
    private val touchOnActivity: TouchPersonOnActivity,
    @ApplicationScope private val appScope: CoroutineScope,
) : InstructionRepository {

    /**
     * Pull every row from the network and upsert into Room with
     * [SyncStatus.SYNCED]. Called once on app start (after auth) and
     * on every Realtime `Change.Instructions` event so the local
     * open-instruction count reflects the full user dataset.
     *
     * Replaces all locally-tracked rows that came from the server
     * (status = SYNCED) and inserts any new ones. Locally-created
     * rows with status = PENDING_INSERT are preserved (they haven't
     * been sent to the server yet, so the network doesn't know about
     * them and we don't want a refresh to clobber them).
     */
    suspend fun refreshFromNetwork() {
        // v2.0.0: no remote to refresh from. The function
        // shape is preserved for any future change. Local Room
        // is the source of truth.
    }

    private fun Instruction.toEntity(): InstructionEntity = InstructionEntity(
        id = id,
        personId = personId,
        direction = direction.name,
        status = status.name,
        source = source.name,
        priority = priority.name,
        title = title,
        rawText = rawText,
        dueAt = dueAt,
        capturedAt = capturedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isSensitive = isSensitive,
        syncStatus = SyncStatus.SYNCED,
        // v2.0 (Hierarchy): audience + due chip + channel.
        audienceKind = audience?.kind,
        audienceTarget = audience?.target,
        audienceLabel = audience?.label,
        audienceIsBroadcast = audience?.isBroadcast ?: false,
        dueAtMs = dueAtMs,
        channel = channel,
        deadlineAtMs = deadlineAtMs,
        updatesJson = InstructionJournal.encode(updates),
        stationId = stationId, matterId = matterId,
    )

    /**
     * v1.5.1 (VAULT-005): the capture-and-save path now writes
     * locally + enqueues a sync_queue row. In vault mode the
     * outbox is never drained, so the row sits in PENDING_INSERT
     * — the durable source of truth. In a future cloud mode the
     * periodic sync worker (or the per-write fire-and-forget
     * drain) would PUSH this row to Supabase and flip the status
     * to SYNCED.
     *
     * The id is a client-generated UUID (Supabase's `id` column
     * defaults to `gen_random_uuid()`; we send the value in the
     * INSERT so the local + server rows match).
     */
    override suspend fun create(
        personId: String?,
        source: Source,
        priority: Priority,
        title: String,
        rawText: String,
        dueAt: String?,
    ): Instruction {
        val now = Instant.now().toString()
        val id = UUID.randomUUID().toString()
        val entity = InstructionEntity(
            id = id,
            personId = personId,
            direction = Direction.OUTGOING.name,
            status = Status.OPEN.name,
            source = source.name,
            priority = priority.name,
            title = title,
            rawText = rawText,
            dueAt = dueAt,
            capturedAt = now,
            createdAt = now,
            updatedAt = now,
            isSensitive = false,
            syncStatus = SyncStatus.PENDING_INSERT,
        )
        // v1.9.8: atomic multi-write. All four side effects (main
        // table, FTS index, sync outbox, last-interaction touch) land
        // in one Room transaction, so a process death or cancellation
        // between them rolls back the partial state. See the class
        // docstring for the failure modes this closes.
        val touchResult = db.withTransaction {
            dao.upsert(
                entity.copy(
                    stationId = com.kaavalan.note.data.subdivision.SubdivisionRepository
                        .resolveCreationContext(db, personId, null, null).stationId,
                ),
            )

            // The FTS row's rowid is the main row's rowid, which
            // is auto-assigned by SQLite on the insert above. Read
            // it back inside the same transaction so we don't
            // race against a concurrent insert.
            val newRowid = ftsDao.maxInstructionRowid() ?: 0L
            ftsDao.upsert(
                InstructionFtsEntity(
                    rowid = newRowid,
                    title = title,
                    rawText = rawText,
                    personId = personId,
                    capturedAt = now,
                ),
            )
            enqueueInsert(id)
            // v2.0 Tier 2 (§2.3): auto-snooze. A new instruction
            // counts as activity for the person; bump their
            // lastInteractionAt so the decay view drops them out of
            // the "haven't touched" list. No-op on free-floating
            // instructions (personId == null). Returns the touched
            // personId (or null) so the caller can observe the side
            // effect; the value is not used by [create] itself.
            touchOnActivity.touch(personId)
        }
        // v18: read the saved row back rather than returning the pre-copy `entity`.
        // The station snapshot is applied inside the transaction above, so the local
        // `entity` value no longer describes what is on disk; a caller that trusted it
        // would carry a null stationId into the UI and into any follow-up write.
        return requireNotNull(dao.getById(id)) { "Instruction $id was not persisted" }.toDomain()
    }

    /**
     * v1.5.1 (VAULT-005): local snapshot of the mirror. Used by
     * callers that previously relied on a Supabase round-trip.
     * `is_sensitive` rows are filtered out for the same defensive
     * reason as the network path.
     */
    override suspend fun fetchAll(): List<Instruction> =
        dao.snapshot()
            .filter { !it.isSensitive }
            .map { it.toDomain() }

    /**
     * v1.5.1 (VAULT-005): the interface-level PATCH. Reads the
     * current row, applies the status / completedAt /
     * droppedReason / isSensitive changes, refreshes updatedAt,
     * writes PENDING_UPDATE, and enqueues a sync_queue row.
     */
    override suspend fun update(
        id: String,
        status: Status,
        completedAt: String?,
        droppedReason: String?,
        isSensitive: Boolean,
    ): Instruction = db.withTransaction {
        val current = dao.getById(id) ?: error("Instruction $id not found in local mirror")
        val now = Instant.now().toString()
        val updated = current.copy(
            status = status.name,
            completedAt = completedAt,
            droppedReason = droppedReason,
            isSensitive = isSensitive,
            updatedAt = now,
            syncStatus = SyncStatus.PENDING_UPDATE,
        )
        // v18: UPDATE, not INSERT OR REPLACE. A REPLACE deletes the existing row before
        // re-inserting it, and `instruction_tags` references `instructions(id)` with
        // ON DELETE CASCADE — so editing a row's status used to silently drop every
        // label attached to it. The row already exists here by construction.
        dao.updateExisting(updated)
        // v18: this row's own rowid, not MAX(rowid). MAX is the newest instruction in the
        // table, which on an edit is somebody else's row: the old code overwrote a
        // different note's search index and left this one stale.
        ftsDao.rowidForInstruction(id)?.let { rowid ->
            ftsDao.upsert(
                InstructionFtsEntity(
                    rowid = rowid,
                    title = updated.title,
                    rawText = updated.rawText,
                    personId = updated.personId,
                    capturedAt = updated.capturedAt,
                ),
            )
        }
        enqueueUpdate(id)
        updated.toDomain()
    }

    /**
     * v1.5.1 (VAULT-005): the [InstructionRepository] wrapper for
     * [markDone]. Aligns with the Supabase signature: the caller
     * provides the [completedAt] timestamp (the original
     * Room-side `markDone(id)` used `Instant.now()` internally;
     * we honour the explicit value here).
     */
    override suspend fun markDone(id: String, completedAt: String) {
        dao.updateStatus(
            id = id,
            status = Status.DONE.name,
            updatedAt = completedAt,
            completedAt = completedAt,
            droppedReason = null,
            syncStatus = SyncStatus.PENDING_UPDATE,
        )
        enqueueUpdate(id)
    }

    /**
     * v1.5.1 (VAULT-005): the [InstructionRepository] wrapper for
     * [markDropped]. The [at] timestamp is used as the
     * `updatedAt` value.
     */
    override suspend fun markDropped(id: String, reason: String?, at: String) {
        dao.updateStatus(
            id = id,
            status = Status.DROPPED.name,
            updatedAt = at,
            completedAt = null,
            droppedReason = reason,
            syncStatus = SyncStatus.PENDING_UPDATE,
        )
        enqueueUpdate(id)
    }

    // ---- v2.0 (Hierarchy): audience + due chip + channel ----

    override suspend fun createWithAudience(
        personId: String?,
        audience: AudienceRef?,
        source: Source,
        priority: Priority,
        title: String,
        rawText: String,
        dueAt: String?,
        dueAtMs: Long?,
        channel: String?,
        direction: Direction,
        stationId: String?,
        matterId: String?,
    ): Instruction {
        val now = Instant.now().toString()
        val id = UUID.randomUUID().toString()
        val entity = InstructionEntity(
            id = id,
            personId = personId,
            direction = direction.name,
            status = Status.OPEN.name,
            source = source.name,
            priority = priority.name,
            title = title,
            rawText = rawText,
            dueAt = dueAt,
            capturedAt = now,
            createdAt = now,
            updatedAt = now,
            isSensitive = false,
            syncStatus = SyncStatus.PENDING_INSERT,
            audienceKind = audience?.kind,
            audienceTarget = audience?.target,
            audienceLabel = audience?.label,
            audienceIsBroadcast = audience?.isBroadcast ?: false,
            dueAtMs = dueAtMs,
            channel = channel,
        )
        // v18: creation and the work-context link land in ONE transaction. A capture
        // started from a matter must not be able to leave a saved-but-unlinked
        // instruction behind: if the chosen station or matter turns out to be archived,
        // hidden, or in another vault, the validation throws and the whole insert rolls
        // back, so the officer keeps their draft instead of a misleading half-saved note.
        db.withTransaction {
            val responsible = personId ?: audience?.target?.takeIf { audience.kind == "PERSON" }
            val context = com.kaavalan.note.data.subdivision.SubdivisionRepository
                .resolveCreationContext(db, responsible, stationId, matterId)
            dao.upsert(entity.copy(stationId = context.stationId, matterId = context.matterId))
            val newRowid = ftsDao.maxInstructionRowid() ?: 0L
            ftsDao.upsert(
                InstructionFtsEntity(
                    rowid = newRowid,
                    title = title,
                    rawText = rawText,
                    personId = personId,
                    capturedAt = now,
                ),
            )
            enqueueInsert(id)
            touchOnActivity.touch(personId)
        }
        return requireNotNull(dao.getById(id)) { "Instruction $id was not persisted" }.toDomain()
    }

    override suspend fun setAudience(id: String, audience: AudienceRef?) {
        val now = Instant.now().toString()
        dao.setAudience(
            id = id,
            audienceKind = audience?.kind,
            audienceTarget = audience?.target,
            audienceLabel = audience?.label,
            audienceIsBroadcast = audience?.isBroadcast ?: false,
            now = now,
            syncStatus = SyncStatus.PENDING_UPDATE,
        )
        enqueueUpdate(id)
    }

    override suspend fun setDueChip(id: String, dueAtMs: Long?) {
        val now = Instant.now().toString()
        dao.setDueChip(
            id = id,
            dueAt = dueAtMs?.let { Instant.ofEpochMilli(it).toString() },
            dueAtMs = dueAtMs,
            now = now,
            syncStatus = SyncStatus.PENDING_UPDATE,
        )
        enqueueUpdate(id)
    }

    override suspend fun setChannel(id: String, channel: String?) {
        val now = Instant.now().toString()
        dao.setChannel(
            id = id,
            channel = channel,
            now = now,
            syncStatus = SyncStatus.PENDING_UPDATE,
        )
        enqueueUpdate(id)
    }

    // ---- existing direct-call helpers (unchanged) ----

    /**
     * v1.1: mark an instruction DONE. Sets `status = DONE`,
     * `completedAt = now()`, refreshes `updatedAt` (so the brief
     * "needs you today" 7-day window resets — a re-opened row
     * is not "carried over"), and queues a PENDING_UPDATE for the
     * sync outbox.
     *
     * The wire side ([SupabaseInstructionRepository.markDone]) is a
     * thin PATCH that hits PostgREST with the same id; the sync
     * engine drains the outbox when the network is available.
     */
    suspend fun markDone(id: String) {
        val now = Instant.now().toString()
        dao.updateStatus(
            id = id,
            status = Status.DONE.name,
            updatedAt = now,
            completedAt = now,
            droppedReason = null,
            syncStatus = SyncStatus.PENDING_UPDATE,
        )
        enqueueUpdate(id)
    }

    /**
     * v1.1: mark an instruction DROPPED. Sets `status = DROPPED`,
     * `droppedReason = reason`, refreshes `updatedAt`. The row stays
     * in Room (the spec's silent drop is the carriedOver > 30 days
     * rule, not a user-initiated drop).
     */
    suspend fun markDropped(id: String, reason: String?) {
        val now = Instant.now().toString()
        dao.updateStatus(
            id = id,
            status = Status.DROPPED.name,
            updatedAt = now,
            completedAt = null,
            droppedReason = reason,
            syncStatus = SyncStatus.PENDING_UPDATE,
        )
        enqueueUpdate(id)
    }

    /**
     * v1.1: re-open a closed instruction. Used when the user
     * "un-done"s a row or restores a dropped one. Status flips back
     * to OPEN; completedAt/droppedReason are cleared; updatedAt
     * refreshes so the 7-day brief window restarts.
     */
    suspend fun reopen(id: String) {
        val now = Instant.now().toString()
        dao.updateStatus(
            id = id,
            status = Status.OPEN.name,
            updatedAt = now,
            completedAt = null,
            droppedReason = null,
            syncStatus = SyncStatus.PENDING_UPDATE,
        )
        enqueueUpdate(id)
    }

    /**
     * v1.1: update a row's [is_sensitive] flag. The sync engine
     * filters sensitive rows on the way out, so flipping this on
     * for an already-synced row needs a PATCH to the server too
     * (the server should drop the row from its own copy — defensive
     * even though spec §13 says sensitive rows never hit the server).
     */
    suspend fun setSensitive(id: String, sensitive: Boolean) {
        db.withTransaction {
            val row = dao.getById(id) ?: return@withTransaction
            // v18: UPDATE rather than REPLACE — see [update] for why a REPLACE here
            // cascaded the row's label links away.
            dao.updateExisting(
                row.copy(
                    isSensitive = sensitive,
                    updatedAt = Instant.now().toString(),
                    syncStatus = SyncStatus.PENDING_UPDATE,
                ),
            )
            enqueueUpdate(id)
        }
    }


    /**
     * v1.1: enqueue a single UPDATE row to the sync outbox and
     * fire-and-forget drain. The drain reads the local Room row
     * (it has the canonical lifecycle fields) and PATCHes the
     * server. On success the local row's `syncStatus` flips to
     * `SYNCED`. On failure the entry stays in the outbox and is
     * retried on the next drain (or app start).
     *
     * The payload is empty because the drain reads the row from
     * Room directly (the canonical source of truth) — the
     * sync_queue only carries `(table, rowId, op)`.
     */
    private suspend fun enqueueUpdate(id: String) {
        // v2.0.0: no remote to drain to. The sync_queue row is
        // still enqueued (forward-compat) but no drain will
        // ever run. A future v2.x pass that adds cloud sync
        // would re-insert the drain call here.
        syncQueueDao.enqueue(
            SyncQueueEntity(
                table = "instructions",
                rowId = id,
                op = SyncQueueEntity.OP_UPDATE,
                payloadJson = "{}",
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * v1.5.1 (VAULT-005): enqueue an INSERT row to the sync
     * outbox. Same shape as [enqueueUpdate] but with
     * `OP_INSERT`. In vault mode the drain is a no-op (the
     * periodic worker is not scheduled) so the row sits in
     * PENDING_INSERT forever — that's the intended state.
     */
    private suspend fun enqueueInsert(id: String) {
        syncQueueDao.enqueue(
            SyncQueueEntity(
                table = "instructions",
                rowId = id,
                op = SyncQueueEntity.OP_INSERT,
                payloadJson = "{}",
                createdAt = System.currentTimeMillis(),
            )
        )
    }
}

/**
 * v1.5.1 (VAULT-005): reverse of [RoomInstructionRepository.toEntity].
 * Maps the Room row (string enums) to the domain [Instruction]
 * (typed enums).
 */
internal fun InstructionEntity.toDomain(): Instruction = Instruction(
    id = id,
    personId = personId,
    direction = Direction.valueOf(direction),
    status = Status.valueOf(status),
    // Older photo-extraction fixtures/records used OCR before PHOTO became the wire value.
    source = if (source == "OCR") Source.PHOTO else Source.valueOf(source),
    priority = Priority.valueOf(priority),
    title = title,
    rawText = rawText,
    dueAt = dueAt,
    capturedAt = capturedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isSensitive = isSensitive,
    completedAt = completedAt,
    droppedReason = droppedReason,
    // v2.0 (Hierarchy): audience + due chip + channel.
    audience = audienceFromColumns(audienceKind, audienceTarget, audienceLabel),
    dueAtMs = dueAtMs,
    channel = channel,
    deadlineAtMs = deadlineAtMs,
    updates = InstructionJournal.decode(updatesJson),
    stationId = stationId, matterId = matterId,
)
