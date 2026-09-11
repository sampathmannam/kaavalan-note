package com.kaavalan.note.data.instructions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * M1 domain model for a tracked instruction. Mirrors the `instructions`
 * table created in M0's `0001_init.sql`. See spec §4.2.
 *
 *  - [direction] is `OUTGOING` for the M1 capture-and-save flow
 *    (the user is delegating / tracking an instruction they gave).
 *  - [status] is `OPEN` on creation; the M2 nudge flow moves it through
 *    `ACK_PENDING` → `IN_PROGRESS` → `DONE`.
 *  - [source] is `TEXT` for M1 text captures. `VOICE` and `PHOTO` land
 *    in M2 alongside the Whisper / ML Kit OCR pipelines.
 *  - [title] is a 5-7 word human-readable label. M1 derives it from
 *    `action + " — " + person` (or just `action` if no person is named).
 *    M2 will have the LLM produce a cleaner title in the prompt.
 *  - [rawText] is the verbatim capture the user typed. The audit trail.
 *  - [dueAt] is the ISO 8601 timestamp from the LLM's `due_at`. `null`
 *    if the user didn't mention a time cue.
 *  - [capturedAt] is `now()` at create time (we set it explicitly because
 *    the column has no DB default).
 */
data class Instruction(
    val id: String,
    @SerialName("person_id") val personId: String?,
    val direction: Direction,
    val status: Status,
    val source: Source,
    val priority: Priority,
    val title: String,
    @SerialName("raw_text") val rawText: String,
    @SerialName("due_at") val dueAt: String?,
    @SerialName("captured_at") val capturedAt: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    // v1.0: spec §13. When true, this row never syncs to Supabase.
    @SerialName("is_sensitive") val isSensitive: Boolean = false,
    // v1.1: lifecycle fields. Set by markDone / markDropped.
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("dropped_reason") val droppedReason: String? = null,
    // v2.0 (Hierarchy): the audience pointer. See [AudienceRef].
    @SerialName("audience") val audience: AudienceRef? = null,
    // v2.0 (Hierarchy): manual due-chip (epoch millis).
    @SerialName("due_at_ms") val dueAtMs: Long? = null,
    // v2.0 (Hierarchy): outbound delivery channel.
    @SerialName("channel") val channel: String? = null,
    val deadlineAtMs: Long? = null,
    val updates: List<InstructionUpdate> = emptyList(),
    val stationId: String? = null,
    val matterId: String? = null,
)

/** Wire values match the `instruction_direction` Postgres enum. */
enum class Direction { INCOMING, OUTGOING, SELF }

/** Wire values match the `instruction_status` Postgres enum. */
enum class Status {
    OPEN,
    ACK_PENDING,
    IN_PROGRESS,
    WAITING_ON_OTHER,
    REPORTED_DONE,
    DONE,
    CARRIED_OVER,
    DROPPED,
}

/** Wire values match the `instruction_source` Postgres enum. */
enum class Source { VOICE, TEXT, PHOTO, MCP }

/** Wire values match the `instruction_priority` Postgres enum. */
enum class Priority { LOW, NORMAL, HIGH, URGENT }
