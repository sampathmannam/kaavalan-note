package com.kaavalan.note.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room mirror of the `instructions` table. Mirrors
 * [com.kaavalan.note.data.instructions.Instruction]; the `syncStatus`
 * column follows the same state machine as [PersonEntity].
 *
 * v1.1: added `completedAt` and `droppedReason` columns for the
 * mark-done / mark-drop flow (Room v8). The Postgres schema already
 * had these columns from M0; the Room mirror only just learned to
 * read/write them. The repository sets `completedAt = now()` on
 * markDone and `droppedReason` on markDropped.
 *
 * v2.0 (Tier 1.5): added `nextActionAt: Long?` for the date
 * picker on each instruction. Optional; the user can leave it
 * blank. `null` = "no scheduled next action".
 * v2.0 Tier 2 fields (migration v10 -> v11):
 *  - [caseType]: optional typed-block marker for §2.8 ("Case" | "Witness"
 *    | "FIR" | "Other" | null). Null = freeform instruction.
 *  - [urgency]: §2.10 worry-box marker. "normal" | "worry" | "worry_with_date".
 *  - [reviewAtEpochDay]: §2.10 "worry with date" — the day the user
 *    wants to revisit the worry capture. `LocalDate.toEpochDay()`.
 */
@Entity(
    tableName = "instructions",
    indices = [
        Index(value = ["status"]),
        Index(value = ["personId"]),
        Index(value = ["dueAt"]),
        Index(value = ["syncStatus"]),
        Index(value = ["urgency"]),
        Index(value = ["audienceKind"]),
        Index(value = ["audienceTarget"]),
        Index(value = ["dueAtMs"]),
        Index(value = ["channel"]),
        // v18 (subdivision CRM): the recorded work context. The station / matter work
        // lists and the review counts filter on these columns on every emission.
        Index(value = ["stationId"]),
        Index(value = ["matterId"]),
    ],
)

data class InstructionEntity(
    @PrimaryKey val id: String,
    val personId: String?,
    val direction: String,
    val status: String,
    val source: String,
    val priority: String,
    val title: String,
    val rawText: String,
    val dueAt: String?,
    val capturedAt: String,
    val createdAt: String,
    val updatedAt: String,
    // v1.0: is_sensitive flag (spec §13). When true, the row
    // never syncs to Supabase; it lives in the local SQLCipher
    // mirror only. The sync engine filters these out before any
    // network read/write.
    val isSensitive: Boolean = false,
    val syncStatus: String = SyncStatus.SYNCED,
    // v1.1: lifecycle fields. Set by mark-done / mark-drop.
    val completedAt: String? = null,
    val droppedReason: String? = null,
    // v2.0 (Tier 1.5): optional scheduled next action (epoch millis).
    val nextActionAt: Long? = null,
    // v2.0 Tier 2 (§2.8): typed-block marker. Null = freeform
    // instruction. Valid values: "Case" | "Witness" | "FIR" | "Other".
    val caseType: String? = null,
    // v2.0 Tier 2 (§2.10): worry-box marker. "normal" (default) |
    // "worry" | "worry_with_date". Worry rows render in the
    // WorryBox section on Today.
    val urgency: String = "normal",
    // v2.0 Tier 2 (§2.10): if urgency == "worry_with_date", the
    // epoch-day on which the user wants to revisit the worry.
    val reviewAtEpochDay: Long? = null,
    // v2.0 (Hierarchy): the audience pointer denormalised onto the
    // row. See [com.kaavalan.note.data.instructions.AudienceRef].
    // The four columns together reconstruct the sealed type
    // (PERSON | DESIGNATION | STATION | ALL); `audienceIsBroadcast`
    // is true for the three non-PERSON variants. All four are
    // `null` for the pre-v2.0 single-person case (the [personId]
    // is the only audience).
    val audienceKind: String? = null,
    val audienceTarget: String? = null,
    val audienceLabel: String? = null,
    val audienceIsBroadcast: Boolean = false,
    // v2.0 (Hierarchy): manual due-chip (epoch millis). Independent
    // of the LLM's [dueAt] ISO string.
    val dueAtMs: Long? = null,
    // v2.0 (Hierarchy): outbound delivery channel (SMS / WHATSAPP
    // / "SMS,WHATSAPP" for both). `null` = "no dispatch attempted".
    val channel: String? = null,
    // v17: deadline is independent of the existing reminder pair. Historical notes have no deadline.
    val deadlineAtMs: Long? = null,
    // The journal belongs to this aggregate and is appended inside a Room transaction.
    @androidx.room.ColumnInfo(defaultValue = "'[]'") val updatesJson: String = "[]",
    val stationId: String? = null,
    val matterId: String? = null,
)
