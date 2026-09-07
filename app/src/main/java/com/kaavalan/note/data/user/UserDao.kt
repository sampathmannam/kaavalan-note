package com.kaavalan.note.data.user

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserEntity)

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): UserEntity?

    @Query("SELECT * FROM users WHERE deviceOwner = 1 LIMIT 1")
    suspend fun deviceOwner(): UserEntity?

    @Query("SELECT * FROM users WHERE deviceOwner = 1 LIMIT 1")
    fun observeDeviceOwner(): Flow<UserEntity?>

    /**
     * v2.1.1 (security): a real `UPDATE` that
     * exercises the write path without deleting +
     * reinserting the row.
     *
     * v2.1.0's [DatabasePreflight] used
     * [upsert] (which is `@Insert(onConflict = REPLACE)`)
     * to "no-op" the device-owner row's `displayName`.
     * REPLACE actually **deletes** the existing row
     * and inserts a new one with a new rowid, which:
     *
     *  1. Skews the `id`-to-rowid mapping (a row
     *     updated 1,000 times has 1,000 different
     *     rowids in WAL).
     *  2. Triggers foreign-key cascades for any child
     *     tables that reference the user — the user
     *     row is briefly gone.
     *  3. Makes the read-back `equals` check in
     *     `DatabasePreflight` unreliable (the rowid
     *     field is part of Room's identity but not
     *     part of the `data class UserEntity`'s
     *     `equals`, so this is benign for the preflight
     *     — but the wasted work isn't).
     *
     * The fix: a real `UPDATE` that touches the row
     * in place. The `displayName = displayName`
     * assignment is a literal no-op in terms of
     * stored values, but SQLite still exercises the
     * write path (and the IO that the preflight is
     * trying to verify).
     *
     * Returns the number of rows updated (0 if the
     * device-owner row is missing, 1 on success).
     */
    @Query("UPDATE users SET displayName = displayName WHERE id = :id")
    suspend fun touch(id: String): Int

    /** How many rows currently claim to be the device owner. */
    @Query("SELECT COUNT(*) FROM users WHERE deviceOwner = 1")
    suspend fun countDeviceOwners(): Int

    /**
     * v2.1.2 (data-integrity): the single write path that may set
     * `deviceOwner = 1`.
     *
     * Until v2.1.2 the "exactly one device owner" invariant was
     * enforced by a partial unique index created in
     * `AppDatabase.MIGRATION_14_15`. That index did not match the
     * plain index `UserEntity` declares, so Room's
     * `validateMigration` rejected every v14 -> v15 upgrade and the
     * app crashed on launch for anyone upgrading from a v1.8.0-era
     * install. The index had to become a plain one to match the
     * entity, which moved the invariant here.
     *
     * `@Transaction` makes the check-then-insert atomic: two
     * concurrent callers cannot both observe zero owners and both
     * insert. Returns true if this call created the row, false if an
     * owner already existed.
     */
    @Transaction
    suspend fun insertDeviceOwnerIfAbsent(user: UserEntity): Boolean {
        require(user.deviceOwner) { "insertDeviceOwnerIfAbsent requires deviceOwner = true" }
        if (countDeviceOwners() > 0) return false
        upsert(user)
        return true
    }
}
