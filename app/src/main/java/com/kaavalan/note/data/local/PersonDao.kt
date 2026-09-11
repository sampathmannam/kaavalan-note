package com.kaavalan.note.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kaavalan.note.data.local.entities.PersonEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `persons` table.
 *
 * **Read path** is Flow-based so the HomeViewModel can collect from
 * Room directly. Room is the single source of truth for the UI.
 *
 * **Write path** is suspend; the repository wraps each call in a
 * coroutine and decides whether to enqueue a sync op afterwards.
 */
@Dao
interface PersonDao {
    @androidx.room.Update
    suspend fun updateExisting(person: PersonEntity)

    /**
     * v18: restore / import writes.
     *
     * [upsert] and [upsertAll] are `INSERT OR REPLACE`, which DELETES the existing row
     * before re-inserting it. `important_date` and `person_link` both reference
     * `persons(id)` with ON DELETE CASCADE, so replacing a person wipes their important
     * dates and relationships. Room's `@Upsert` is INSERT-then-UPDATE: no delete, so no
     * cascade, and a repeated restore stays genuinely idempotent.
     */
    @androidx.room.Upsert
    suspend fun save(person: PersonEntity)

    @androidx.room.Upsert
    suspend fun saveAll(persons: List<PersonEntity>)


    @Query("SELECT * FROM persons ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PersonEntity>>

    /**
     * v2.0 T3-1 (deniable vault): the HomeViewModel observes
     * `persons WHERE vaultMode = :mode` so the list shows only
     * the rows the user has access to in the current mode. The
     * `vaultMode` column is indexed (see PersonEntity @Index).
     */
    @Query("SELECT * FROM persons WHERE vaultMode = :mode ORDER BY name COLLATE NOCASE ASC")
    fun observeAllInMode(mode: String): Flow<List<PersonEntity>>

    /**
     * v1.6.4: total count of persons, used by
     * [com.kaavalan.note.data.local.AppInitializer] to decide
     * whether to auto-load the synthetic fixture on first
     * launch (debug builds only). One-shot read, not a Flow.
     */
    @Query("SELECT COUNT(*) FROM persons")
    suspend fun count(): Int

    /**
     * v1.9.1 (PROD-READINESS-P3-P2-#3 wiring): one-shot
     * count of "quiet" people for the Decay widget surface.
     * A person is quiet when:
     *  - their `lastInteractionAt` is non-null (i.e. the user
     *    has touched them at least once — never-touched is
     *    "New", not "Quiet", matching the [DecayViewModel]
     *    filter at line 66), AND
     *  - their `lastInteractionAt` is older than the
     *    threshold (default 60 days for the widget, which
     *    mirrors the v1.9.0 widget description string
     *    "60+ days since last interaction").
     *
     * The threshold is passed as an epoch-ms value so the
     * caller controls the cutoff (60d * 86_400_000).
     */
    @Query(
        "SELECT COUNT(*) FROM persons " +
            "WHERE lastInteractionAt IS NOT NULL " +
            "AND lastInteractionAt < :thresholdMs",
    )
    suspend fun countQuietSince(thresholdMs: Long): Int

    /**
     * v2.0 T3-1: count of persons in the OTHER mode (so the
     * HomeScreen can render an "X items in vault" affordance
     * when the user is in visible mode and there are hidden
     * rows; in hidden mode the same affordance is suppressed).
     */
    @Query("SELECT COUNT(*) FROM persons WHERE vaultMode = :mode")
    fun observeCountInMode(mode: String): Flow<Int>

    /**
     * v2.0 T3-1: flip a single person's vault mode. The repository
     * also updates the person's instructions (and propagates the
     * sync outbox) so the rest of the UI doesn't see orphan rows.
     */
    @Query("UPDATE persons SET vaultMode = :mode, updatedAt = :updatedAt, syncStatus = :status WHERE id = :id")
    suspend fun setVaultMode(id: String, mode: String, updatedAt: String, status: String)

    @Query("SELECT * FROM persons WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PersonEntity?

    /**
     * v1.1.1: reactive read for the PersonDetailViewModel. Emits
     * every time the row changes (e.g. when [setSensitive]
     * toggles the local flag). The previous one-shot
     * [getById] would not re-emit on a local update, so the
     * detail screen's "Mark as sensitive" button stayed in its
     * old state after a tap even though the local Room row
     * flipped correctly.
     */
    @Query("SELECT * FROM persons WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<PersonEntity?>

    @Query("SELECT * FROM persons WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): PersonEntity?

    @Query("SELECT * FROM persons ORDER BY name COLLATE NOCASE ASC")
    suspend fun snapshot(): List<PersonEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(person: PersonEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(persons: List<PersonEntity>)

    @Query("UPDATE persons SET syncStatus = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setSyncStatus(id: String, status: String, updatedAt: String)

    @Query("UPDATE persons SET updatedAt = :updatedAt, syncStatus = :status, name = :name, designation = :designation, station = :station, phone = :phone WHERE id = :id")
    suspend fun updateLocal(
        id: String,
        name: String,
        designation: String?,
        station: String?,
        phone: String?,
        updatedAt: String,
        status: String,
    )

    /**
     * v1.1: spec §13 - flip the local-only flag. The sync engine
     * filters sensitive rows on the way out, so toggling on for
     * an already-synced row needs a PATCH to the server too
     * (the server should drop the row from its own copy).
     */
    @Query("UPDATE persons SET isSensitive = :sensitive, updatedAt = :updatedAt, syncStatus = :status WHERE id = :id")
    suspend fun setSensitive(id: String, sensitive: Boolean, updatedAt: String, status: String)

    @Query("DELETE FROM persons WHERE id = :id")
    suspend fun deleteById(id: String)

    // v1.6.2: bulk delete for the developer fixture loader. Not
    // referenced by any production code path.
    @Query("DELETE FROM persons")
    suspend fun deleteAll()

    // v2.0 Tier 2 (§2.1, §2.2, §2.3) ----

    /**
     * Auto-snooze on activity. Updates `lastInteractionAt` (and
     * bumps `updatedAt`) on any new capture / instruction / photo
     * for the person. Idempotent: a no-op if the row's
     * `lastInteractionAt` is already newer (so calling this from
     * a tight loop is cheap).
     */
    @Query("UPDATE persons SET lastInteractionAt = :nowMs, updatedAt = :updatedAt WHERE id = :personId")
    suspend fun touch(personId: String, nowMs: Long, updatedAt: String)

    /**
     * v1.8.0 (PROD-READINESS-P1-#6): the inverse of [touch] for
     * the "Mark as recent" undo path. Restores both
     * `lastInteractionAt` (nullable) and `updatedAt` in a
     * single UPDATE. The non-null branch is the common case
     * (the user touched the person, then undid the touch);
     * the null branch is the "I marked a never-touched person
     * as recent by mistake; undo should put them back in the
     * new / never-touched group" edge case.
     */
    @Query("UPDATE persons SET lastInteractionAt = :lastInteractionAtMs, updatedAt = :updatedAt WHERE id = :personId")
    suspend fun restoreLastInteraction(personId: String, lastInteractionAtMs: Long?, updatedAt: String)

    /**
     * §2.2: change the relationship tier. Used by the
     * "Cadence chip" picker in PersonDetailScreen.
     */
    @Query("UPDATE persons SET tier = :tier, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setTier(id: String, tier: String, updatedAt: String)

    /**
     * §2.2: set or clear the per-person cadence override.
     * Pass `null` to clear and fall back to the tier default.
     */
    @Query("UPDATE persons SET cadenceOverrideDays = :days, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setCadenceOverride(id: String, days: Int?, updatedAt: String)
}
