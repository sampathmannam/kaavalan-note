package com.kaavalan.note.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room mirror of the `persons` table. One row per person the user
 * has saved. The Room copy is the source of truth for the UI; the
 * Supabase mirror is reconciled by [com.kaavalan.note.data.local.RoomPersonRepository]
 * on every read (cold start) and on every Realtime event.
 *
 * **Sync state machine** ([syncStatus]):
 *  - `SYNCED`        - Room and Supabase are in agreement. Default after
 *                      an initial pull, or after a write completes.
 *  - `PENDING_INSERT` - created locally, not yet sent to Supabase. The
 *                      row carries a client-side UUID ([id]); on
 *                      successful drain the id stays (PostgREST accepts
 *                      the client id on insert).
 *  - `PENDING_UPDATE` - updated locally, the change has not been
 *                      POSTed to Supabase. The local row carries the
 *                      intended new values; drain sends a PATCH.
 *  - `PENDING_DELETE` - marked for deletion. The row stays in Room
 *                      until the drain succeeds, so the user sees the
 *                      deletion immediately; on success the row is
 *                      removed from Room.
 *
 * **Conflict resolution** (M2-T8): if the server's `updated_at` is
 * newer than the local one when the sync queue drains, the local
 * change is dropped and a row is inserted into `sync_conflicts` for
 * the audit trail. For M2-T6 the implementation is last-write-wins
 * by [updatedAt] (client-side wins on local writes, server-side wins
 * on remote Realtime events).
 *
 * **v2.0 Tier 2 fields (migrations v10 -> v11):**
 *  - [tier]: relationship tier — one of "Inner", "Active", "Periodic",
 *    "Dormant". Drives the default cadence for the decay view (§2.2).
 *  - [cadenceOverrideDays]: optional user-set per-person override that
 *    replaces the tier default (§2.2).
 *  - [lastInteractionAt]: epoch millis of the most recent capture
 *    / instruction / photo for this person. Powers the
 *    "Haven't touched in N days" view (§2.1) and is auto-bumped on
 *    activity (§2.3).
 */
@Entity(
    tableName = "persons",
    indices = [
        Index(value = ["name"]),
        Index(value = ["syncStatus"]),
        Index(value = ["lastInteractionAt"]),
        // v2.0 T3-1: the deniable-vault filter. The HomeViewModel
        // observes persons WHERE vaultMode = :activeMode; without
        // this index the list filter is a full scan on every
        // emission.
        Index(value = ["vaultMode"]),
        // v18 (subdivision CRM): the station list's per-station staff count and the
        // staff segment's filter both scope on these two columns.
        Index(value = ["stationId"]),
        Index(value = ["isStaff"]),
    ],
)

data class PersonEntity(
    @PrimaryKey val id: String,
    val name: String,
    val designation: String?,
    val station: String?,
    val phone: String?,
    val userId: String,
    val createdAt: String,
    val updatedAt: String,
    // v1.0: is_sensitive flag (spec §13). When true, the row
    // never syncs to Supabase; the person + their instructions
    // live in the local SQLCipher mirror only.
    val isSensitive: Boolean = false,
    val syncStatus: String = SyncStatus.SYNCED,
    // v2.0 Tier 2 (§2.2): relationship tier. Stored as a string for
    // forward-compatibility; valid values are "Inner", "Active",
    // "Periodic", "Dormant". Defaults to "Active" so existing
    // people get the 30-day cadence.
    val tier: String = "Active",
    // v2.0 Tier 2 (§2.2): per-person override of the tier's default
    // cadence. `null` means "use the tier default". Stored in days.
    val cadenceOverrideDays: Int? = null,
    // v2.0 Tier 2 (§2.1, §2.3): epoch millis of the last user
    // activity for this person. Null = "never touched"; the decay
    // view hides these (they go in a separate "New" group, not
    // "haven't touched"). Bumped by TouchPersonOnActivity on any
    // new capture / instruction / photo for the person.
    val lastInteractionAt: Long? = null,
    // v2.0 T3-1: deniable vault (Cryptee-style "ghost folder"
    // lite). Two values: `visible` (default) or `hidden`. The
    // home list filters on this column so a single Room DB
    // hosts two logical vaults; switching modes is a UI
    // affordance backed by a singleton holder. NOT
    // cryptographically deniable — a forensic adversary can see
    // the `vaultMode` column on disk. The settings copy in
    // Settings → Threat model spells this out.
    val vaultMode: String = "visible",
    val stationId: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val isStaff: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "1") val staffActive: Boolean = true,
    @androidx.room.ColumnInfo(defaultValue = "''") val responsibilities: String = "",
)
