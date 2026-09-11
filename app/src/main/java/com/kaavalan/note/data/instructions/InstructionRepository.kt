package com.kaavalan.note.data.instructions

/**
 * Repository for the `instructions` table. M1 only writes (no reads —
 * the Today tab + Today brief are M4). M3 added [fetchAll] for the
 * initial sync-on-launch used by the M3-T5 open-instruction badge.
 */
interface InstructionRepository {
    /**
     * Insert a new instruction. The implementation sets the direction
     * to `OUTGOING` and the status to `OPEN` — M1 only ever creates
     * instructions on the user's own behalf, in the OPEN state. The
     * [capturedAt] is set to `now()` on the server via the column's
     * `default now()` for `created_at` and an explicit client-side
     * value for `captured_at` (the column has no default).
     *
     * @return the inserted row (with server-generated id + timestamps).
     */
    suspend fun create(
        personId: String?,
        source: Source,
        priority: Priority,
        title: String,
        rawText: String,
        dueAt: String?,
    ): Instruction

    /**
     * M3-T5: pull every instruction row the calling user is allowed
     * to see (RLS scopes to `auth.uid()`). Used by the
     * `RoomInstructionRepository.refreshFromNetwork` initial sync so
     * the People-list badge reflects the user's real open-instruction
     * count, including rows captured on other devices.
     */
    suspend fun fetchAll(): List<Instruction>

    /**
     * v1.1: PATCH an instruction row on the server. Used by the
     * sync engine to drain PENDING_UPDATE rows from the outbox when
     * the user has changed a row's status, sensitive flag, etc.
     * Returns the canonical server row (with its `updated_at`).
     */
    suspend fun update(
        id: String,
        status: Status,
        completedAt: String?,
        droppedReason: String?,
        isSensitive: Boolean,
    ): Instruction

    /**
     * v1.1: convenience wrapper for `update(id, DONE, completedAt, null, ...)`.
     */
    suspend fun markDone(id: String, completedAt: String)

    /**
     * v1.1: convenience wrapper for `update(id, DROPPED, null, reason, ...)`.
     */
    suspend fun markDropped(id: String, reason: String?, at: String)

    /**
     * v2.0 (Hierarchy): create a new instruction with an audience pointer.
     *
     * v18 (subdivision CRM): [stationId] and [matterId] carry an explicit work context —
     * a capture started from a matter or a station. Both are validated and applied inside
     * the same transaction as the insert, so a rejected context leaves nothing saved.
     * Left null, the instruction snapshots the responsible contact's current station and
     * is linked to no matter.
     */
    suspend fun createWithAudience(
        personId: String?,
        audience: AudienceRef?,
        source: Source,
        priority: Priority,
        title: String,
        rawText: String,
        dueAt: String?,
        dueAtMs: Long?,
        channel: String?,
        direction: Direction = Direction.OUTGOING,
        stationId: String? = null,
        matterId: String? = null,
    ): Instruction


    /** v2.0 (Hierarchy): replace the audience pointer. `null` clears. */
    suspend fun setAudience(id: String, audience: AudienceRef?)

    /** v2.0 (Hierarchy): set / clear the manual due chip. */
    suspend fun setDueChip(id: String, dueAtMs: Long?)

    /** v2.0 (Hierarchy): set the outbound delivery channel. */
    suspend fun setChannel(id: String, channel: String?)
}
