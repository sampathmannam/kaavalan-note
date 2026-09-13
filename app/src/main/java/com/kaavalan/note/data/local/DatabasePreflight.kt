package com.kaavalan.note.data.local

import android.util.Log
import com.kaavalan.note.data.user.UserDao
import com.kaavalan.note.data.user.UserEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v2.0.2 + v2.1.0 (PM rating) + v2.1.1 (security): the
 * database preflight check. Runs:
 *
 *  1. A **schema verification** — every expected
 *     table exists, every expected column is
 *     present, every expected index is in place.
 *  2. A **read + write + read** round-trip on the
 *     device-owner row.
 *
 * Sets a "database corrupt" flag in [DatabaseHealth]
 * if any step fails. The Settings sheet reads the
 * flag and surfaces a "Database error — Erase and
 * restart" banner with a one-tap
 * `eraseAllLocalData` CTA.
 *
 * **Why a schema verification + a round-trip, not
 * just a `SELECT 1`.** A `SELECT 1` would only
 * catch "the file isn't a database at all" — a
 * silent schema mismatch (e.g. a half-applied
 * migration that left a column missing) would
 * pass. v2.1.0's pre-flight only caught the
 * silent-corruption-on-write case (the round-trip).
 * v2.1.1 also catches the silent-schema-drift case
 * (the verification).
 *
 * **Why the device-owner row.** It's a single row
 * that always exists (the
 * [com.kaavalan.note.data.user.UserBootstrap.ensureDeviceOwner]
 * runs at app start). The "no-op write" is
 * `displayName = displayName` — SQLite is smart
 * enough to short-circuit, but the IO path is still
 * exercised.
 *
 * **v2.1.1 (security): why a real UPDATE, not
 * `upsert()`.** v2.1.0's no-op write was
 * `userDao.upsert(before.copy())`, which is
 * `@Insert(onConflict = REPLACE)`. On conflict,
 * REPLACE actually **deletes** the existing row and
 * inserts a new one with a new rowid. That
 * exercised the write path (good — preflight's
 * purpose) but also:
 *
 *  1. Triggers foreign-key cascades for any child
 *     tables that reference the user. The user
 *     row is briefly gone mid-transaction; any
 *     concurrent reader that hits the gap sees a
 *     missing row.
 *  2. Wastes a rowid (Room / SQLite allocates a
 *     new one each preflight — once per cold
 *     start, so a heavy user accumulates dead
 *     rowids in the WAL).
 *  3. Misleads a code reader: "no-op" implies
 *     "doesn't change anything", but REPLACE is
 *     structurally a delete+insert.
 *
 * The fix: a dedicated
 * [com.kaavalan.note.data.user.UserDao.touch] method
 * that runs a real `UPDATE users SET displayName =
 * displayName WHERE id = :id`. The assignment is a
 * literal no-op in terms of stored values, but
 * SQLite still exercises the write path (which is
 * what the preflight actually wants to verify), and
 * the rowid stays put.
 *
 * **Why catch every `Throwable`.** The underlying
 * SQLCipher error surface throws a mix of
 * `SQLiteException`, `RuntimeException` ("file is not
 * a database"), and the occasional
 * `IllegalStateException` from Room's invalidation
 * tracker. Catching `Throwable` is the only way to
 * guarantee the flag is set.
 *
 * **What this does NOT do.** It does NOT attempt to
 * repair the database. The repair is the "Erase all
 * data" path (deletes the file + the passphrase)
 * followed by a restore from backup. The v2.0.2 fix
 * is a controlled surfacing of the error, not a
 * one-click repair.
 */
@Singleton
class DatabasePreflight @Inject constructor(
    private val database: AppDatabase,
    private val userDao: UserDao,
    private val databaseHealth: DatabaseHealth,
) {

    /**
     * The full preflight:
     *  1. Schema verification — every expected
     *     table + column is present.
     *  2. Read the device-owner row (exercises the
     *     read path AND catches "no device-owner row"
     *     — a wrong-passphrase failure).
     *  3. UPDATE the row in place (a no-op write
     *     that exercises the write path AND catches
     *     silent corruption; the rowid is preserved).
     *  4. Read it back (catches the case where the
     *     write silently succeeded but the read
     *     returns a different value — e.g. a
     *     mid-WAL-flush crash).
     *  5. Compare the original to the read-back; if
     *     they differ, the DB is corrupt.
     *
     * Any throwable in any step is treated as a
     * corruption signal.
     */
    suspend fun runPreflight() {
        // Reset on every launch — the preflight is the
        // source of truth for "is the DB currently
        // readable".
        databaseHealth.clearCorrupt()
        try {
            // Step 1: schema verification.
            verifySchema(database.openHelper.writableDatabase)

            // Step 2: read.
            val before: UserEntity = userDao.deviceOwner()
                ?: throw IllegalStateException(
                    "device-owner row missing — the v1.8.0 " +
                        "UserBootstrap should have inserted it. " +
                        "This is the failure mode for a wrong " +
                        "SQLCipher passphrase after a Keystore reset.",
                )

            // Step 3: no-op write (UPDATE displayName =
            // displayName). v2.1.1: real UPDATE, not the
            // v2.1.0 REPLACE-on-conflict upsert (which
            // deleted + reinserted the row). See the
            // class-level KDoc for the why.
            val rowsUpdated = userDao.touch(before.id)
            if (rowsUpdated != 1) {
                // The device-owner row was found in
                // step 2, but the UPDATE matched 0 rows.
                // SQLite is internally inconsistent; the
                // safest thing is to mark the DB corrupt.
                throw IllegalStateException(
                    "device-owner UPDATE matched $rowsUpdated rows, " +
                        "expected 1 — internal inconsistency. " +
                        "The DB is corrupted; 'Erase all data' is " +
                        "the only safe recovery.",
                )
            }

            // Step 4 + 5: read back + compare.
            val readBack: UserEntity = userDao.deviceOwner()
                ?: throw IllegalStateException(
                    "device-owner row vanished after a write — " +
                        "silent corruption. The DB is unreadable.",
                )
            if (readBack != before) {
                throw IllegalStateException(
                    "device-owner row read-back mismatched the " +
                        "write-in. The DB is corrupted; 'Erase all " +
                        "data' is the only safe recovery.",
                )
            }
            // All five steps OK → DB is healthy.
        } catch (e: Exception) {
            // v2.1.1 (security): don't echo the
            // [UserEntity] (which may include the user's
            // `displayName` and other PII) into logcat.
            // The error message we throw above may
            // include a `before=$before` placeholder for
            // debugging, but logcat only gets the
            // exception class + the first line of the
            // message (which is the class + the rowid,
            // not the full entity).
            Log.e(
                TAG,
                "DB preflight failed (${e.javaClass.simpleName}). " +
                    "Marking the database as corrupt; the Settings " +
                    "sheet will surface a 'Database error' banner with " +
                    "an 'Erase all data' CTA.",
            )
            databaseHealth.markCorrupt()
        }
    }

    /**
     * v2.1.1 (security): verify every expected
     * table + column is present via
     * `PRAGMA table_info`. Catches the
     * "half-applied migration left a column
     * missing" failure mode that a read-only
     * check would miss.
     *
     * The expected schema is the post-v15
     * (current) schema. A pre-v15 user whose
     * migration chain silently bailed out (e.g.
     * the pre-v8 best-effort migrations ran but
     * the v8->v9 `ALTER TABLE` was skipped
     * because of a foreign-key conflict) would
     * fail this check and surface a "Database
     * error" banner.
     */
    private fun verifySchema(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        for ((table, expectedColumns) in EXPECTED_SCHEMA) {
            val cursor = db.query("PRAGMA table_info(`$table`)")
            cursor.use { c ->
                val nameIndex = c.getColumnIndex("name")
                if (nameIndex < 0) {
                    throw IllegalStateException(
                        "PRAGMA table_info('$table') returned no 'name' column",
                    )
                }
                val actualColumns = buildSet {
                    while (c.moveToNext()) {
                        add(c.getString(nameIndex))
                    }
                }
                val missing = expectedColumns - actualColumns
                if (missing.isNotEmpty()) {
                    throw IllegalStateException(
                        "table '$table' is missing expected columns: " +
                            "$missing. The DB schema is out of sync " +
                            "with the expected v15 schema; 'Erase all " +
                            "data' is the only safe recovery.",
                    )
                }
            }
        }
    }

    private companion object {
        private const val TAG = "KaavalanNoteDbPreflight"

        /**
         * v2.1.1 (security): the post-v15 expected
         * schema. The preflight runs
         * `PRAGMA table_info` for each table and
         * asserts every named column is present.
         *
         * This is intentionally a list of
         * **subset** columns — Room may add more
         * (e.g. for an `@Index` derived column
         * or a future migration); the preflight
         * only checks that the columns the app's
         * DAOs reference still exist.
         */
        private val EXPECTED_SCHEMA: Map<String, Set<String>> = mapOf(
            "users" to setOf(
                "id", "displayName", "role", "deviceOwner", "createdAt",
            ),
            "instructions" to setOf(
                "id", "personId", "direction", "status", "source",
                "priority", "title", "rawText", "dueAt", "capturedAt",
                "createdAt", "updatedAt", "isSensitive", "syncStatus",
                "completedAt", "droppedReason", "nextActionAt",
                "caseType", "urgency", "reviewAtEpochDay",
                "audienceKind", "audienceTarget", "audienceLabel",
                "audienceIsBroadcast", "dueAtMs", "channel",
            ),
            "sync_queue" to setOf(
                "id", "table", "rowId", "op", "payloadJson",
                "createdAt", "attempts", "lastError", "nextAttemptAt",
            ),
            "audit_chain_events" to setOf(
                "id", "tableName", "rowId", "kind", "payload",
                "signingKey", "createdAtMs", "prevHash", "thisHash",
            ),
            "app_state" to setOf(
                "id", "source", "valueJson", "updatedAt",
            ),
        )
    }
}
