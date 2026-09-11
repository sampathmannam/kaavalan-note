package com.kaavalan.note.data.person

/**
 * Person domain model. The Room row carries more bookkeeping
 * fields ([com.kaavalan.note.data.local.entities.PersonEntity] has
 * [userId], [createdAt], [updatedAt], [syncStatus]); the domain
 * model exposes only what the UI needs plus [updatedAt] (M2-T8)
 * so the sync engine can do last-write-wins on writes.
 *
 * [updatedAt] is the ISO-8601 timestamp the server (or local
 * creator) set on the row. String, not `Instant`, because the
 * value is fed back into SQL queries and the server's `text`
 * column would round-trip; comparing strings is correct because
 * the format is fixed-width and lexicographic = chronological.
 *
 * v2.0 Tier 2 fields (migrations v10 -> v11):
 *  - [tier] - relationship tier ("Inner" | "Active" | "Periodic"
 *    | "Dormant"). Default "Active".
 *  - [cadenceOverrideDays] - per-person override of the tier
 *    default cadence. Null = "use tier default".
 *  - [lastInteractionAt] - epoch millis of the most recent
 *    activity for this person. Null = "never touched".
 */
data class Person(
    val id: String,
    val name: String,
    val designation: String?,
    val station: String?,
    val phone: String?,
    val updatedAt: String? = null,
    // v1.0: spec §13. When true, this row + its instructions
    // never sync to Supabase.
    val isSensitive: Boolean = false,
    // v2.0 Tier 2 (§2.2): relationship tier.
    val tier: String = "Active",
    // v2.0 Tier 2 (§2.2): per-person cadence override.
    val cadenceOverrideDays: Int? = null,
    // v2.0 Tier 2 (§2.1, §2.3): last-interaction timestamp.
    val lastInteractionAt: Long? = null,
    val stationId: String? = null,
    val isStaff: Boolean = false,
    val staffActive: Boolean = true,
    val responsibilities: String = "",
)
